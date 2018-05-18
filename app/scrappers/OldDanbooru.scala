package soxx.scrappers

import scala.concurrent._

import play.api.libs.ws._
import play.api.libs.json._

import soxx.mongowrapper._

abstract class OldDanbooruScrapper ()
  (
    implicit ws: WSClient,
    mongo: Mongo,
    ec: ExecutionContext
  ) extends GenericScrapper {

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

  override type ScrapperImage = OldDanbooruImage

  override implicit val imageFormat = Json.format[OldDanbooruImage]

  override val apiAddition = "index.php?page=dapi&s=post&q=index"

  override def getPageCount: Future[Int] =
    ws
      .url(s"${baseUrl}/${apiAddition}")
      .get()
      .map { resp =>
        val totalPostCount = (resp.xml \\ "posts" \ "@count").map{ _.text }.head.toInt

        totalPostCount / 100
      }

  override def getPageImagesAndCurrentPage(currentPage: Int): Future[(Seq[OldDanbooruImage], Int)] =
    ws
      .url(s"${baseUrl}/${apiAddition}")
      .addQueryStringParameters(
        ("pid", currentPage.toString),
        ("json", "1")
      )
      .get()
      .map { res => (res.json.as[Seq[OldDanbooruImage]], currentPage) }

  override def scrapperImageToImage(img: OldDanbooruImage): Image =
    Image(
      height = img.height,
      width = img.width,
      tags = img.tags.split(" ").toSeq,
      md5 = img.hash,
      from = Seq(
        From(
          id = img.id,
          name = name,
          imageName = img.image,
          score = img.score,
          post = f"${baseUrl}/index.php?page=post&s=view&id=${img.id}",
          image = f"${baseUrl}/images/${img.directory}/${img.image}",
          thumbnail = f"${baseUrl}/thumbnails/${img.directory}/thumbnail_${img.image}"
        )
      ),
      extension = img.image.substring(img.image.lastIndexOf('.')),
      metadataOnly = true
    )

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

class FurrybooruScrapper ()
  (
    implicit ws: WSClient,
    mongo: Mongo,
    ec: ExecutionContext
  ) extends OldDanbooruScrapper {

  // Furrybooru doesnt support https
  override val baseUrl = "http://furry.booru.org"
  override val name = "furrybooru"
}
