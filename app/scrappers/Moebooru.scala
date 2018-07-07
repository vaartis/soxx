package soxx.scrappers

import scala.concurrent._

import play.api.libs.ws._
import play.api.libs.json._

import soxx.mongowrapper._

class MoebooruScrapper(
  name: String,
  baseUrl: String,
  favicon: String
)(
  implicit ws: WSClient,
  mongo: Mongo,
  ec: ExecutionContext
) extends GenericScrapper(name, baseUrl, favicon) {

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
  override val maxImageFetchingConcurrency = 10

  override type ScrapperImage = MoebooruImage

  override implicit val imageFormat = Json.format[MoebooruImage]

  override val apiAddition = "post"

  override def getImageCount: Future[Int] =
    ws
      .url(s"${baseUrl}/${apiAddition}.xml")
      .get()
      .map { resp => (resp.xml \\ "posts" \ "@count").map{ _.text }.head.toInt }

  override def getPageImagesAndCurrentPage(currentPage: Int): Future[(Seq[MoebooruImage], Int)] =
    ws
      .url(s"${baseUrl}/${apiAddition}.json")
      .addQueryStringParameters(
        ("limit", 100.toString),
        ("page", currentPage.toString),
      )
      .get()
      .map { res => (res.json.as[Seq[MoebooruImage]], currentPage) }

  override def scrapperImageToImage(img: MoebooruImage): Option[Image] = {
    import java.net.URI
    import java.nio.file.Paths

    // getPath is important here, because without it Paths.get hangs
    val fileName = Paths.get(new URI(img.file_url).getPath).getFileName.toString

    Some(
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
    )
  }
}
