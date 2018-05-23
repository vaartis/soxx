package soxx.scrappers

import scala.concurrent._

import play.api.libs.ws._
import play.api.libs.json._

import soxx.mongowrapper._

abstract class MoebooruScrapper ()
  (
    implicit ws: WSClient,
    mongo: Mongo,
    ec: ExecutionContext
  ) extends GenericScrapper {

  case class MoebooruImage(
    id: Int,
    height: Int,
    width: Int,
    score: Int,
    file_url: String,
    preview_url: String,
    tags: String,
    md5: String
  )

  // There doesn't seem to be a real limit,
  // so just set it to 10
  override val maxPageFetchingConcurrency = 10

  override type ScrapperImage = MoebooruImage

  override implicit val imageFormat = Json.format[MoebooruImage]

  override val apiAddition = "post"

  override def getPageCount: Future[Int] =
    ws
      .url(s"${baseUrl}/${apiAddition}.xml")
      .get()
      .map { resp =>
        val totalPostCount = (resp.xml \\ "posts" \ "@count").map{ _.text }.head.toInt

        totalPostCount / 100
      }

  override def getPageImagesAndCurrentPage(currentPage: Int): Future[(Seq[MoebooruImage], Int)] =
    ws
      .url(s"${baseUrl}/${apiAddition}.json")
      .addQueryStringParameters(
        ("limit", 100.toString),
        ("page", currentPage.toString),
      )
      .get()
      .map { res => (res.json.as[Seq[MoebooruImage]], currentPage) }

  override def scrapperImageToImage(img: MoebooruImage): Image = {
    import java.net.URI
    import java.nio.file.Paths

    // getPath is important here, because without it Paths.get hangs
    val fileName = Paths.get(new URI(img.file_url).getPath).getFileName.toString

    Image(
      height = img.height,
      width = img.width,
      tags = img.tags.split(" ").toSeq,
      md5 = img.md5,
      from = Seq(
        From(
          id = img.id,
          name = name,
          imageName = fileName,
          score = img.score,
          post = f"${baseUrl}/post/show/${img.id}",
          image = img.file_url,
          thumbnail = img.preview_url
        )
      ),
      extension = fileName.substring(fileName.lastIndexOf('.')),
      metadataOnly = true
    )
  }
}

class KonachanScrapper ()
  (
    implicit ws: WSClient,
    mongo: Mongo,
    ec: ExecutionContext
  ) extends MoebooruScrapper {

  override val baseUrl = "https://konachan.com"
  override val name = "konachan"
}

class YandereScrapper ()
  (
    implicit ws: WSClient,
    mongo: Mongo,
    ec: ExecutionContext
  ) extends MoebooruScrapper {

  override val baseUrl = "http://yande.re"
  override val name = "yandere"
}

/* This one is mostly videos but it's still moebooru */
class SakugabooruScrapper ()
  (
    implicit ws: WSClient,
    mongo: Mongo,
    ec: ExecutionContext
  ) extends MoebooruScrapper {

  override val baseUrl = "https://www.sakugabooru.com"
  override val name = "sakugabooru"
}
