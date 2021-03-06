/*
 * Copyright 2020 SimplexPortal Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.simplexportal.spatial.index.grid.entrypoints.restful

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, Scheduler}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Directives, RequestContext, Route, RouteResult}
import akka.util.Timeout
import com.simplexportal.spatial.StartUpServerResult
import com.simplexportal.spatial.index.grid.GridProtocol.{GridReply, _}
import com.simplexportal.spatial.index.grid.entrypoints.restful.RestProtocol._
import com.simplexportal.spatial.model.Location
import com.typesafe.config.Config

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object RestServer extends Directives with RestfulJsonProtocol {

  implicit val timeout: Timeout = 2.second

  private def defaultResponseAdapter[T]: PartialFunction[GridReply[T], Route] = {
    case x =>
      complete(
        StatusCodes.InternalServerError -> NotDone(Some(s"Nothing handling response ${x}"))
      )
  }

  private def reply[T](
      resp: Future[GridReply[T]],
      applyOnSuccess: PartialFunction[GridReply[T], Route]
  ): RequestContext => Future[RouteResult] = {
    onComplete(resp) {
      case Success(GridDone()) => complete(Done())
      case Failure(error)      => complete(NotDone(Some(error.getMessage)))
      case Success(reply) =>
        reply.payload.fold(
          error =>
            complete(
              StatusCodes.InternalServerError -> NotDone(Some(error))
            ),
          _ => applyOnSuccess(reply)
        )
    }
  }

  def start(gridIndex: ActorRef[GridRequest], config: Config)(
      implicit executionContext: ExecutionContext,
      scheduler: Scheduler,
      system: ActorSystem
  ): StartUpServerResult = {

    val interface = config.getString("simplexportal.spatial.entrypoint.restful.interface")
    val port = config.getInt("simplexportal.spatial.entrypoint.restful.port")

    val route =
      concat(
        nodeRoutes(gridIndex),
        wayRoutes(gridIndex),
        batchRoutes(gridIndex),
        algorithmsRoutes(gridIndex)
      )

    StartUpServerResult("SimplexSpatial Restful", Http().bindAndHandle(route, interface, port))
  }

  private def nodeRoutes(gridIndex: ActorRef[GridRequest])(
      implicit scheduler: Scheduler
  ) =
    path("node" / LongNumber) { id =>
      concat(
        get {
          def responseAdapter[T]: PartialFunction[GridReply[T], Route] = {
            case GridGetNodeReply(Right(None)) => complete(StatusCodes.NotFound)
            case GridGetNodeReply(Right(Some(node))) =>
              complete(Node(node.id, node.location.lat, node.location.lon, node.attributes))
          }
          reply(
            gridIndex
              .ask[GridGetNodeReply](ref => GridGetNode(id, ref)),
            responseAdapter
          )
        },
        put {
          entity(as[AddNodeBody]) { body =>
            reply(
              gridIndex
                .ask[GridACK](ref => GridAddNode(id, body.lat, body.lon, body.attributes, Some(ref))),
              defaultResponseAdapter
            )
          }
        }
      )
    }

  private def wayRoutes(gridIndex: ActorRef[GridRequest])(
      implicit scheduler: Scheduler
  ) =
    path("way" / LongNumber) { id =>
      concat(
        get {

          def responseAdapter[T]: PartialFunction[GridReply[T], Route] = {
            case GridGetWayReply(Right(None)) => complete(StatusCodes.NotFound)
            case GridGetWayReply(Right(Some(way))) =>
              complete(
                Way(
                  way.id,
                  way.nodes.map(n => Node(n.id, n.location.lat, n.location.lon, n.attributes)),
                  way.attributes
                )
              )
          }

          reply(
            gridIndex
              .ask[GridGetWayReply](ref => GridGetWay(id, ref)),
            responseAdapter
          )
        },
        put {
          entity(as[AddWayBody]) { body =>
            reply(
              gridIndex
                .ask[GridACK](ref => GridAddWay(id, body.nodes, body.attributes, Some(ref))),
              defaultResponseAdapter
            )
          }
        }
      )
    }

  private def batchRoutes(gridIndex: ActorRef[GridRequest])(
      implicit scheduler: Scheduler
  ) =
    path("batch") {
      put {
        entity(as[AddBatchBody]) { body =>
          reply(
            gridIndex
              .ask[GridACK](ref =>
                GridAddBatch(
                  body.nodes.map(n => GridAddNode(n.id, n.lat, n.lon, n.attributes)) ++
                    body.ways.map(n => GridAddWay(n.id, n.nodes, n.attributes)),
                  Some(ref)
                )
              ),
            defaultResponseAdapter
          )
        }
      }
    }

  private def algorithmsRoutes(gridIndex: ActorRef[GridRequest])(
      implicit scheduler: Scheduler
  ) = {
    path("algorithm" / "nearest" / "node") {
      get {
        def responseAdapter[T]: PartialFunction[GridReply[T], Route] = {
          case GridNearestNodeReply(Right(nodes)) =>
            complete(NearestNodes(nodes.map(n => Node(n.id, n.location.lat, n.location.lon, n.attributes))))
        }
        parameters('lat.as[Double], 'lon.as[Double]) { (lat, lon) =>
          reply(
            gridIndex
              .ask[GridNearestNodeReply](ref => GridNearestNode(Location(lat, lon), ref)),
            responseAdapter
          )
        }
      }
    }
  }
}
