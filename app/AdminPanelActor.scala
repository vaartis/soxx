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

  case class ImboardCountersMsg(imboard: String)
  case class IsIndexingMsg(imboard: String)
  case class ScrapperActionMsg(imboard: String, action: String)

  implicit val iif = Json.format[IsIndexingMsg]
  implicit val iaf = Json.format[ScrapperActionMsg]
  implicit val imc = Json.format[ImboardCountersMsg]

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
        case "sub-to-image-counters" =>
          import org.mongodb.scala._
          import org.mongodb.scala.model.Filters._
          import org.mongodb.scala.model.Updates.combine

          val data = msg.as[ImboardCountersMsg]
          val imageCollection = mongo.db.getCollection("images")

          def getAndSendData {
                for (
                  indexedImageCount <- imageCollection.count(Document("from" -> Document("$elemMatch" -> Document("name" -> data.imboard))));
                  downloadedImageCount <- imageCollection.count(
                    combine(
                      equal("metadataOnly", false),
                      Document("from" -> Document("$elemMatch" -> Document("name" -> data.imboard)))
                    )
                  )
                ) {
                  out ! Json.obj(
                    "tp" -> "image-counters-updated",
                    "imboard" -> data.imboard,
                    "value" -> Json.obj(
                      "indexedImageCount" -> indexedImageCount,
                      "downloadedImageCount" -> downloadedImageCount
                    )
                  )
                }
          }

          imageCollection
            .watch()
            .foreach { change => if (change.getOperationType != OperationType.INVALIDATE) getAndSendData }

          getAndSendData

        case "imboard-scrapper-status" =>
          val data = msg.as[IsIndexingMsg]

          system
            .actorSelection(system / "scrapper-supervisor" / f"${data.imboard}-scrapper")
            .resolveOne
            .flatMap { case actorRef =>
              actorRef ? ScrapperStatusMsg
            }
            .andThen { case Success(scrapperStatus) =>
              out ! Json.obj(
                "tp" -> "imboard-scrapper-status",
                "imboard" -> data.imboard,
                "value" -> Json.toJson(scrapperStatus.asInstanceOf[ScrapperStatus])
              )
            }
        case "imboard-scrapper-action" =>
          val data = msg.as[ScrapperActionMsg]

          system
            .actorSelection(system / "scrapper-supervisor" / f"${data.imboard}-scrapper")
            .resolveOne
            .andThen { case Success(actorRef) =>
              actorRef ! (data.action match {
                case "start-indexing" => StartIndexing()
                case "stop-indexing" => StopIndexing

                case "start-downloading" => StartDownloading
                case "stop-downloading" => StopDownloading
              })
            }
            .andThen { case _ =>
              self ! Json.obj("tp" -> "imboard-scrapper-status", "imboard" -> data.imboard)
            }
      }
  }
}
