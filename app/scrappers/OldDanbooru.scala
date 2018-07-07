package soxx.scrappers

import scala.concurrent._

import play.api.inject.Injector
import play.api.libs.ws._
import play.api.libs.json._

class OldDanbooruScrapper(
  name: String,
  baseUrl: String,
  favicon: String,

  injector: Injector
) extends GenericScrapper(name, baseUrl, favicon, injector) {

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

  // Boards tests do not allow more then 8 connections,
  // if you see that the indexing just stops, this might be
  // the reason it doesnt progress (e.g. at 10 most just hang)
  override val maxPageFetchingConcurrency = 8
  override val maxImageFetchingConcurrency = 8

  override type ScrapperImage = OldDanbooruImage

  override implicit val imageFormat = Json.format[OldDanbooruImage]

  override val apiAddition = "index.php?page=dapi&s=post&q=index"

  override def getImageCount: Future[Int] =
    ws
      .url(s"${baseUrl}/${apiAddition}")
      .get()
      .map { resp => (resp.xml \\ "posts" \ "@count").map{ _.text }.head.toInt }

  override def getPageImagesAndCurrentPage(currentPage: Int): Future[(Seq[OldDanbooruImage], Int)] =
    ws
      .url(s"${baseUrl}/${apiAddition}")
      .addQueryStringParameters(
        ("pid", currentPage.toString),
        ("json", "1")
      )
      .get()
      .map { res => (res.json.as[Seq[OldDanbooruImage]], currentPage) }

  override def scrapperImageToImage(img: OldDanbooruImage): Option[Image] =
    Some(
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
    )

}
