pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://maven.aliyun.com/repository/public/") {
            content { excludeGroupByRegex("com\\.github\\..*") }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://maven.aliyun.com/repository/public/") {
            content { excludeGroupByRegex("com\\.github\\..*") }
        }
        maven("https://repo1.maven.org/maven2/")
        // banner
        maven("https://s01.oss.sonatype.org/content/groups/public")
    }
}
include(":app")
// include(":common")
// project(":common").projectDir = File("../android-common/common")
