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
    case status @ ScrapperStatus(imboard, _, _) =>
      out ! Json.obj(
        "tp" -> "imboard-scrapper-status",
        "imboard" -> imboard,
        "value" -> Json.toJson(status)
      )

    case msg: JsObject =>
      (msg \ "tp").as[String] match {
        case "sub-to-image-counters" =>
          import org.mongodb.scala._
          import org.mongodb.scala.model.Facet
          import org.mongodb.scala.model.Filters._
          import org.mongodb.scala.model.Aggregates.{ out => _, _ }
          import org.mongodb.scala.model.Updates.combine

          import soxx.helpers.Helpers

          val data = msg.as[ImboardCountersMsg]

          // Junky syntax here, so that on type level it seems like
          // the function accecpts something of type Unit, but it actually ignores it since there's no such value
          // This function is then throttled to only work once every second since mongodb sends a lot of
          // notifications..
          //
          // FIXME: make something better, this is just ugly
          def getAndSendData_ = { _: Unit =>
            mongo.db
              .getCollection("images")
              .aggregate(Seq(
                `match`(equal("from.name", data.imboard)),
                facet(
                  Facet("indexed", count("value")),
                  Facet("downloaded", `match`(equal("metadataOnly", false)), count("value"))
                )
              ))
              .foreach { doc =>
                // FIXME: ouch, do something with that ugly decoding stuff
                val indexedCount = {
                  val arr = doc("indexed").asArray()
                  if (arr.size > 0) { arr.get(0).asDocument()("value").asInt32.intValue } else { 0 }
                }
                val downloadedCount = {
                  val arr = doc("downloaded").asArray()
                  if (arr.size > 0) { arr.get(0).asDocument()("value").asInt32.intValue } else { 0 }
                }

                out ! Json.obj(
                  "tp" -> "image-counters-updated",
                  "imboard" -> data.imboard,
                  "value" -> Json.obj(
                    "indexedImageCount" -> indexedCount,
                    "downloadedImageCount" -> downloadedCount
                  )
                )
              }
          }
          val getAndSendData = Helpers.debounce(1.second)(getAndSendData_)

          mongo.db
            .getCollection("images")
            .watch()
            .foreach { change => if (change.getOperationType != OperationType.INVALIDATE) getAndSendData(()) }

          getAndSendData(())

        case "imboard-scrapper-status" =>
          val data = msg.as[IsIndexingMsg]

          for (imboardScrapper <- system.actorSelection(system / "scrapper-supervisor" / f"${data.imboard}-scrapper").resolveOne) {
            // Ask the status, it will send ScrapperStatus back and
            // it will be handled by another match arm
            imboardScrapper ! ScrapperStatusMsg
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
