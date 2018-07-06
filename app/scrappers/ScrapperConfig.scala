package soxx.scrappers

case class ScrapperConfig(
  `type`: String,
  `base-url`: String,
  favicon: String = "favicon.ico",
  enabled: Boolean = true
)
