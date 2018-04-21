package soxx.scrappers

import java.util.concurrent.{Executors}
import scala.concurrent._
import scala.concurrent.duration._

import play.api.libs.ws._
import play.api.libs.json._
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

abstract class OldDanbooruScrapper ()
  (
    implicit ws: WSClient,
    mongo: Mongo,
    ec: ExecutionContext
  ) extends Actor {

  case class Image(
    id: Int,
    height: Int,
    width: Int,
    score: Int,
    name: String,
    directory: String,
    tags: String,
    md5: String
  )

  /* Converters  */

  implicit val imageReads: Reads[Image] = (
    (JsPath \ "id").read[Int] and
      (JsPath \ "height").read[Int] and
      (JsPath \ "width").read[Int] and
      (JsPath \ "score").read[Int] and
      (JsPath \ "image").read[String] and
      (JsPath \ "directory").read[String] and
      (JsPath \ "tags").read[String] and
      (JsPath \ "hash").read[String]
  )(Image.apply _)

  implicit def image2Document(i: Image): Document = {
    Document(
      "originalID" -> i.id,
      "height" -> i.height,
      "width" -> i.width,
      "score" -> i.score,
      "name" -> i.name,
      "extension" -> i.name.substring(i.name.lastIndexOf('.')),
      "tags" -> i.tags.split(" ").toSeq,
      "md5" -> i.md5,

      "originalPost" -> s"${baseUrl}/index.php?page=post&s=view&id=${i.id}",
      "originalImage" -> s"${baseUrl}/images/${i.directory}/${i.id}",

      "metadataOnly" -> true
    )
  }

  /* --- */

  val name: String

  val baseUrl: String
  val apiAddition = "index.php?page=dapi&s=post&q=index"

  var materializer: Option[ActorMaterializer] = None

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
            .map { case (json_resp, currentPage) => (json_resp.json.as[Seq[Image]], currentPage) }
            .runForeach { case (images, currentPage) =>
              import org.mongodb.scala.model.Updates._
              import org.mongodb.scala.model.Filters._

              val insertOperations: Seq[WriteModel[Document]] = images.map { img =>
                ReplaceOneModel(
                  equal("originalID", img.id),
                  image2Document(img), // Implicit convertion doesnt work here ._.
                  UpdateOptions().upsert(true)
                )
              }

              Await.result(
                mongo.DB
                  .getCollection(name)
                  .bulkWrite(insertOperations, BulkWriteOptions().ordered(false))
                  .toFuture(),
                5 seconds
              )

              println(s"Finished page ${currentPage} of ${name}")
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
}

class SafebooruScrapper ()
  (
    implicit ws: WSClient,
    mongo: Mongo,
    ec: ExecutionContext
  ) extends OldDanbooruScrapper {

  override val baseUrl = "https://safebooru.org"
  override val name = "safebooru"
}
