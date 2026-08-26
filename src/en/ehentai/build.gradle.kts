import io.github.keiyoushi.gradle.api.ContentWarning


plugins {
    alias(kei.plugins.extension)
}


keiyoushi {
    name = "E-Hentai"
    versionCode = 14
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"


    source {
        name = "E-Hentai"
        baseUrl = "https://e-hentai.org"
        lang = "en"
    }


    deeplink {
        path("/..*")
    }
}

