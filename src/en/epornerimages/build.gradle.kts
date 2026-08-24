import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Eporner Images"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    source {
        name = "Eporner Images"
        baseUrl = "https://www.eporner.com"
        lang = "en"
    }
    deeplink {
        host("www.eporner.com")
        path("/pics/.*")
        path("/best-collections/.*")
        path("/gallery/.*")
        path("/photo/.*")
    }
}
