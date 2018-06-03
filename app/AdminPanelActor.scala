package soxx.admin

import scala.language.postfixOps

import scala.concurrent._
import scala.concurrent.duration._
import scala.util._

import akka.actor._
import akka.util.Timeout
import akka.pattern.ask
import com.mongodb.client.model.changestream.{ ChangeStreamDocument, FullDocument, OperationType }
import play.api.libs.json._

import soxx.mongowrapper._
import soxx.scrappers._

object AdminPanelActor {
  def props(out: ActorRef)(
    implicit mongo: Mongo,
    system: ActorSystem,
    ec: ExecutionContext
  ) = Props(new AdminPanelActor(out))
}

class AdminPanelActor(out: ActorRef)(
  implicit mongo: Mongo,
  system: ActorSystem,
  ec: ExecutionContext
) extends Actor {

  case class IsIndexingMsg(imboard: String)
  case class IndexingActionMsg(imboard: String, action: String)

  implicit val iif = Json.format[IsIndexingMsg]
  implicit val iaf = Json.format[IndexingActionMsg]

  implicit val actorResolveTimeout: Timeout = 5 seconds

  override def preStart = {
    mongo.db
      .getCollection[BoardInfo]("imboard_info")
      .watch()
      .fullDocument(FullDocument.UPDATE_LOOKUP)
      .foreach { change =>
        change.getOperationType match {
          case OperationType.DELETE =>
            // If the board was deleted, send the deleted event with
            // the deleted imageboard name
            out ! Json.obj(
              "tp" -> "imboard-deleted",
              "value" -> change.getDocumentKey.getString("_id").toString
            )
          case OperationType.UPDATE | OperationType.REPLACE =>
            // Otherwise send the update event with the whole
            // updated things
            out ! Json.obj(
              "tp" -> "imboard-updated",
              "value" -> Json.toJson(change.getFullDocument)
            )
          case OperationType.INSERT | OperationType.INVALIDATE =>
            ???
        }
      }
  }

  override def receive = {
    case msg: JsObject =>
      (msg \ "tp").as[String] match {
        case "imboard-is-indexing" =>
          val data = msg.as[IsIndexingMsg]

          system
            .actorSelection(system / "scrapper-supervisor" / f"${data.imboard}-scrapper")
            .resolveOne
            .flatMap { case actorRef =>
              actorRef ? IsIndexing
            }
            .andThen { case Success(isIndexing) =>
              out ! Json.obj(
                "tp" -> "imboard-is-indexing",
                "imboard" -> data.imboard,
                "value" -> isIndexing.asInstanceOf[Boolean]
              )
            }
        case "imboard-indexing-action" =>
          val data = msg.as[IndexingActionMsg]

          system
            .actorSelection(system / "scrapper-supervisor" / f"${data.imboard}-scrapper")
            .resolveOne
            .andThen { case Success(actorRef) =>
              data.action match {
                case "start" => actorRef ! StartIndexing()
                case "stop" => actorRef ! StopIndexing
              }
            }
            .andThen { case _ =>
              self ! Json.obj("tp" -> "imboard-is-indexing", "imboard" -> data.imboard)
            }
      }
  }
}
