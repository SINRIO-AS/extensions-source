import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Eporner Images"
    versionCode = 3
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    source {
        lang = "all"
        baseUrl = "https://www.eporner.com"
    }
    deeplink {
        host("www.eporner.com")
        path("/pics/.*")
        path("/best-collections/.*")
        path("/gallery/.*")
        path("/photo/.*")
    }
}
