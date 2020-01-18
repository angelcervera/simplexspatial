/*
 * Copyright 2019 SimplexPortal Ltd
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

package com.simplexportal.spatial.index.grid.lookups

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import com.simplexportal.spatial.index.grid.CommonInternalSerializer
import com.simplexportal.spatial.index.grid.tile.TileIdx

import scala.annotation.tailrec

// TODO: Generalize LookUp
object WayLookUpActor {

  sealed trait Message extends CommonInternalSerializer

  trait Response extends Message

  trait ACK extends Response

  case class Done() extends ACK

  case class NotDone(error: String) extends ACK

  case class GetResponse(id: Long, maybeWayEntityIds: Option[Set[TileIdx]])
      extends Response

  case class GetsResponse(gets: Seq[GetResponse]) extends Response

  trait Command extends Message

  case class Put(
      id: Long,
      wayEntityId: TileIdx,
      replyTo: Option[ActorRef[ACK]]
  ) extends Command

  case class PutBatch(puts: Seq[Put], replyTo: Option[ActorRef[ACK]])
      extends Command

  case class Get(id: Long, replyTo: ActorRef[GetResponse]) extends Command

  case class Gets(ids: Seq[Long], replyTo: ActorRef[GetsResponse])
      extends Command

  trait Event extends Message

  case class Putted(id: Long, wayEntityId: TileIdx) extends Event

  case class PuttedBatch(puts: Seq[Putted]) extends Event

  def apply(indexId: String, partitionId: String): Behavior[Command] =
    Behaviors.setup { _ =>
      EventSourcedBehavior[Command, Event, Map[Long, Set[TileIdx]]](
        persistenceId = PersistenceId(indexId, partitionId),
        emptyState = Map.empty,
        commandHandler = (state, command) => onCommand(state, command),
        eventHandler = (state, event) => applyEvent(state, event)
      )
    }

  private def onCommand(
      table: Map[Long, Set[TileIdx]],
      command: Command
  ): Effect[Event, Map[Long, Set[TileIdx]]] = {
    command match {
      case Get(id, replyTo) =>
        replyTo ! GetResponse(id, table.get(id))
        Effect.none
      case Gets(ids, replyTo) =>
        replyTo ! GetsResponse(ids.map(id => GetResponse(id, table.get(id))))
        Effect.none
      case Put(id, wayEntityId, replyTo) =>
        Effect.persist(Putted(id, wayEntityId)).thenRun { _ =>
          replyTo.foreach(_ ! Done())
        }
      case PutBatch(puts, replyTo) =>
        Effect
          .persist(
            PuttedBatch(puts.map(put => Putted(put.id, put.wayEntityId)))
          )
          .thenRun { _ =>
            replyTo.foreach(_ ! Done())
          }
    }
  }

  private def applyEvent(
      table: Map[Long, Set[TileIdx]],
      event: Event
  ): Map[Long, Set[TileIdx]] =
    event match {
      case Putted(id, wayEntityId) =>
        table.multiAdd(id, wayEntityId)
      case PuttedBatch(puts) =>
        table.multiAdd(puts.map { case Putted(k, v) => (k, v) })
    }

  // TODO: Export to a common class.
  private implicit class MultiValueMap(table: Map[Long, Set[TileIdx]]) {

    def multiAdd(k: Long, v: TileIdx): Map[Long, Set[TileIdx]] =
      table + (k -> (table.getOrElse(k, Set.empty) + v))

    def multiAdd(items: Seq[(Long, TileIdx)]): Map[Long, Set[TileIdx]] = {
      @tailrec
      def rec(
          remaining: Seq[(Long, TileIdx)],
          acc: Map[Long, Set[TileIdx]]
      ): Map[Long, Set[TileIdx]] = remaining match {
        case Nil => acc
        case head :: tail =>
          head match {
            case (k, v) => rec(tail, acc.multiAdd(k, v))
          }
      }
      rec(items, table)
    }

  }
}
