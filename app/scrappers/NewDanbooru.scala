package soxx.scrappers

import scala.concurrent._

import play.api.libs.ws._
import play.api.libs.json._

import soxx.mongowrapper._

abstract class NewDanbooruScrapper
  (
    implicit ws: WSClient,
    mongo: Mongo,
    ec: ExecutionContext
  ) extends GenericScrapper {

  case class NewDanbooruImage(
    id: Int,
    image_height: Int,
    image_width: Int,
    score: Int,
    file_url: Option[String], // Danbooru can have posts with no actual files in it somehow
    preview_file_url: Option[String],
    file_ext: Option[String],
    tag_string: String,
    md5: Option[String],
  )

  override val maxPageFetchingConcurrency = 10

  override type ScrapperImage = NewDanbooruImage

  override implicit val imageFormat = Json.format[NewDanbooruImage]

  override val apiAddition = "posts"

  override def getPageCount: Future[Int] =
    ws
      .url(f"${baseUrl}/counts/posts.json") // This isnt really properly documented
      .get()
      .map { resp =>
        val totalPostCount = (resp.json \ "counts" \ "posts").as[Int]

        // New danbooru actually hard-limits to 200 images
        // Old limited to 100
        totalPostCount / 1
      }

  override def getPageImagesAndCurrentPage(currentPage: Int): Future[(Seq[NewDanbooruImage], Int)] =
    ws
      .url(s"${baseUrl}/${apiAddition}.json")
      .addQueryStringParameters(
        ("limit", 1.toString),
        ("page", currentPage.toString),
      )
      .get()
      .map { res => (res.json.as[Seq[NewDanbooruImage]], currentPage) }

  override def scrapperImageToImage(img: NewDanbooruImage): Option[Image] = {
    import java.net.URI
    import java.nio.file.Paths

    // Image isn't broken / deleted
    if (!img.file_url.isEmpty) {
      // getPath is important here, because without it Paths.get hangs
      val fileName = Paths.get(new URI(img.file_url.get).getPath).getFileName.toString

      Some(
        Image(
          height = img.image_height,
          width = img.image_width,
          tags = img.tag_string.split(" "),
          md5 = img.md5.get,
          from = Seq(
            From(
              id = img.id,
              name = name,
              imageName = fileName,
              score = img.score,
              post = f"${baseUrl}/posts/${img.id}",
              image = img.file_url.get,
              thumbnail = img.preview_file_url.get
            )
          ),
          extension = f".${img.file_ext}",
          metadataOnly = true
        )
      )
    } else { None }
  }
}

// Errors out on 1000th+ page
class DanbooruScrapper
  (
    implicit ws: WSClient,
    mongo: Mongo,
    ec: ExecutionContext
  ) extends NewDanbooruScrapper {

  override val baseUrl = "https://danbooru.donmai.us"
  override val name = "danbooru"
}
