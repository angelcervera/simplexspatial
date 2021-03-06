/*
 * Copyright 2020 SimplexPortal Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.simplexportal.spatial.index.grid.sessions.addbatch

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import com.simplexportal.spatial.index.grid.Grid
import com.simplexportal.spatial.index.grid.GridProtocol.{GridACK, GridBatchCommand, GridDone, GridNotDone}
import com.simplexportal.spatial.index.grid.tile.actor.TileIdx
import com.simplexportal.spatial.index.grid.tile.{actor => tile}
import com.simplexportal.spatial.index.lookup.node.NodeLookUpProtocol
import com.simplexportal.spatial.index.lookup.way.WayLookUpProtocol

protected trait UpdateIndices extends DataDistribution with Adapter {

  private def updateNodeLookups(
      nodesPerTileLookup: Map[String, Seq[(Long, TileIdx)]]
  )(implicit sharding: ClusterSharding, replyTo: ActorRef[AnyRef]): Int =
    nodesPerTileLookup.foldLeft(0) {
      case (counter, (shardId, items)) =>
        sharding.entityRefFor(Grid.NodeLookUpTypeKey, shardId) !
          NodeLookUpProtocol.PutBatch(items.map {
            case (id, tileIdx) => NodeLookUpProtocol.Put(id, tileIdx, None)
          }, Some(replyTo))
        counter + 1
    }

  private def updateWaysLookups(
      waysPerTileLookup: Map[String, Seq[(Long, TileIdx)]]
  )(implicit sharding: ClusterSharding, replyTo: ActorRef[AnyRef]): Int = waysPerTileLookup.foldLeft(0) {
    case (counter, (shardId, items)) =>
      sharding.entityRefFor(Grid.WayLookUpTypeKey, shardId) !
        WayLookUpProtocol.PutBatch(items.map {
          case (id, tileIdx) => WayLookUpProtocol.Put(id, tileIdx, None)
        }, Some(replyTo))
      counter + 1
  }

  private def updateTiles(
      cmdsPerTile: Map[tile.TileIdx, Seq[GridBatchCommand]]
  )(implicit sharding: ClusterSharding, replyTo: ActorRef[AnyRef]): Int = cmdsPerTile.foldLeft(0) {
    case (counter, (tileIdx, cmds)) =>
      sharding.entityRefFor(Grid.TileTypeKey, tileIdx.entityId) !
        tile.AddBatch(cmds.map(cmd => cmd.toTileProtocol()), Some(replyTo))
      counter + 1
  }

  def updateIndices(
      cmds: Seq[GridBatchCommand],
      locationsIdx: Map[Long, TileIdx],
      maybeReplyTo: Option[ActorRef[GridACK]]
  )(implicit sharding: ClusterSharding): Behavior[ForeignResponse] = Behaviors.setup[ForeignResponse] { context =>
    implicit val adapter = adapters(context)

    val cmdsPerTileIdx = groupByTileIdx(cmds, locationsIdx)
    val (nodes, ways) = splitLookUps(cmdsPerTileIdx)

    // FIXME: Tiles should be updated after lookups, and not in parallel.
    val expectedResponses = updateNodeLookups(nodes) + updateWaysLookups(ways) + updateTiles(cmdsPerTileIdx)

    collectAddCommandsResponses(expectedResponses, Seq.empty, maybeReplyTo)
  }

  private def collectAddCommandsResponses(
      expectedResponses: Int,
      errors: Seq[String],
      maybeReplyTo: Option[ActorRef[GridACK]]
  ): Behavior[ForeignResponse] = {

    def reply(errors: Seq[String]) =
      maybeReplyTo.foreach { replyTo =>
        errors match {
          case Seq() => replyTo ! GridDone()
          case _     => replyTo ! GridNotDone(errors.mkString("\n"))
        }
      }

    def next(
        remainingResponses: Int,
        errors: Seq[String]
    ): Behavior[ForeignResponse] =
      if (remainingResponses == 0) {
        reply(errors)
        Behaviors.stopped
      } else {
        rec(remainingResponses, errors)
      }

    def rec(
        remainingResponses: Int,
        errors: Seq[String]
    ): Behavior[ForeignResponse] =
      Behaviors.receiveMessagePartial {
        case DoneWrapper() =>
          next(remainingResponses - 1, errors)
        case NotDoneWrapper(error) =>
          next(remainingResponses - 1, errors :+ error)
      }

    rec(expectedResponses, Seq.empty)
  }

}
