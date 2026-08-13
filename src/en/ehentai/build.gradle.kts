import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "E-Hentai"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "E-Hentai"
        baseUrl = "https://e-hentai.org"
        lang = "en"
    }

    deeplink {
        path("/..*")
    }
}
