package soxx.scrappers

import java.util.concurrent.{Executors}
import scala.concurrent._
import scala.concurrent.duration._

import play.api.libs.ws._
import play.api.libs.json._
import play.api.Logger
import play.api.libs.functional.syntax._
import scala.util._

import akka._
import akka.actor._
import akka.stream._
import akka.stream.scaladsl._

import soxx.mongowrapper._
import org.mongodb.scala._
import org.mongodb.scala.bson.{BsonTransformer, BsonDocument}
import org.mongodb.scala.model._
import com.mongodb.MongoCommandException
import com.mongodb.client.result.UpdateResult

abstract class OldDanbooruScrapper ()
  (
    implicit ws: WSClient,
    mongo: Mongo,
    ec: ExecutionContext
  ) extends Actor {

  case class OldDanbooruImage(
    id: Int,
    height: Int,
    width: Int,
    score: Int,
    image: String,
    directory: String,
    tags: String,
    hash: String
  )

  implicit val oldDanbooruImageFormat = Json.format[OldDanbooruImage]

  /* --- */

  def name: String

  def baseUrl: String
  def apiAddition = "index.php?page=dapi&s=post&q=index"
  def favicon = "favicon.ico"

  def logger = Logger(this.getClass)

  var materializer: Option[ActorMaterializer] = None

  override def preStart() {
    mongo.db
      .getCollection[BoardInfo]("imboard_info")
      .replaceOne(
        Document(
          "_id" -> name,
        ),
        BoardInfo(name, favicon = f"${baseUrl}/${favicon}"),
        UpdateOptions().upsert(true)
      )
      .subscribe { (_: UpdateResult) =>
        logger.info("Updated 'imboard_info'")
      }
  }

  def startIndexing(fromPage: Int, toPage: Option[Int] = None): Unit = {
    if (materializer == None) {
      implicit val actualMaterializer = ActorMaterializer()(context)
      materializer = Some(actualMaterializer)

      ws
        .url(s"${baseUrl}/${apiAddition}")
        .addQueryStringParameters(("pid", fromPage.toString))
        .get()
        .map { resp =>
          val totalPostCount = (resp.xml \\ "posts" \ "@count").map{ _.text }.head.toInt

          val pagesCount = {
            val pagesCount = totalPostCount / 100
            toPage match {
              case Some(toPage) => if (pagesCount < toPage) { pagesCount } else { toPage }
              case None => pagesCount
            }
          }

          Source(1 to (pagesCount + 1))
            .mapAsyncUnordered(8){ currentPage =>
              // Get the pages, maximum 8 at a time
              // TODO: make this configurable
              // We also pass the current page downstream

              ws
                .url(s"${baseUrl}/${apiAddition}")
                .addQueryStringParameters(
                  ("pid", currentPage.toString),
                  ("json", "1")
                )
                .get()
                .map { res => (res, currentPage) }
            }
            .map { case (json_resp, currentPage) => (json_resp.json.as[Seq[OldDanbooruImage]], currentPage) }
            .runForeach { case (images, currentPage) =>
              import org.mongodb.scala.model.Updates._
              import org.mongodb.scala.model.Filters._

              val insertOperations = images.map { img =>
                ReplaceOneModel(
                  equal("originalID", img.id),
                  Image(
                    originalID = img.id,
                    height = img.height,
                    width = img.width,
                    score = img.score,
                    name = img.image,
                    tags = img.tags.split(" ").toSeq,
                    md5 = img.hash,
                    from = name,
                    extension = img.image.substring(img.image.lastIndexOf('.')),

                    originalPost = s"${baseUrl}/index.php?page=post&s=view&id=${img.id}",
                    originalImage = s"${baseUrl}/images/${img.directory}/${img.image}",
                    originalThumbnail = s"${baseUrl}/thumbnails/${img.directory}/thumbnail_${img.image}",

                    metadataOnly = true
                  ),
                  UpdateOptions().upsert(true)
                )
              }

              mongo.db
                .getCollection[Image]("images")
                .bulkWrite(insertOperations, BulkWriteOptions().ordered(false))
                .subscribe(
                  (_: BulkWriteResult) => logger.info(s"Finished page ${currentPage}")
                )
            }
        }
    }
  }

  def stopIndexing(): Unit = {
    materializer.foreach { mat =>
      mat.shutdown()
      materializer = None
    }
  }

  override def receive = {
    case StartIndexing(page) =>
      startIndexing(page)

    case StopIndexing =>
      stopIndexing()
  }

  override def postStop = {
    logger.info("Stopped")
  }
}

class SafebooruScrapper ()
  (
    implicit ws: WSClient,
    mongo: Mongo,
    ec: ExecutionContext
  ) extends OldDanbooruScrapper {

  override def baseUrl = "https://safebooru.org"
  override def name = "safebooru"
}
