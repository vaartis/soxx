package soxx.scrappers

import scala.concurrent._

import play.api.inject.Injector
import play.api.libs.json._

class NewDanbooruScrapper(
  name: String,
  baseUrl: String,
  favicon: String,

  injector: Injector
) extends GenericScrapper(name, baseUrl, favicon, injector)
    with traits.JSONImageCount {

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
  override val maxImageFetchingConcurrency = 10

  override type ScrapperImage = NewDanbooruImage

  override implicit val imageFormat = Json.format[NewDanbooruImage]

  override val apiAddition = "posts"

  override val imageCountAddition = "counts/posts.json" // This isnt really properly documented

  override def extractImageCount(from: JsValue) =
    (from \ "counts" \ "posts").as[Int]

  // New danbooru actually hard-limits to 200 images
  // Old limited to 100
  override val pageSize = 200

  override def getPageImagesAndCurrentPage(currentPage: Int): Future[(Seq[NewDanbooruImage], Int)] =
    ws
      .url(s"${baseUrl}/${apiAddition}.json")
      .addQueryStringParameters(
        ("limit", pageSize.toString),
        ("page", currentPage.toString),
      )
      .get()
      .map { res => (res.json.as[Seq[NewDanbooruImage]], currentPage) }

  override def scrapperImageToImage(img: NewDanbooruImage): Option[Image] = {
    // Image isn't broken / deleted
    if (!img.file_url.isEmpty) {

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
              score = img.score,
              post = f"${baseUrl}/posts/${img.id}",
              image = img.file_url.get,
              thumbnail = img.preview_file_url.get
            )
          ),
          extension = f".${img.file_ext.get}"
        )
      )
    } else { None }
  }
}
