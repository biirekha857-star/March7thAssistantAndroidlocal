plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("assetManifest") {
            id = "com.march7th.hkrpg.asset-manifest"
            implementationClass = "com.march7th.hkrpg.buildlogic.AssetManifestPlugin"
        }
        register("i18nVerify") {
            id = "com.march7th.hkrpg.i18n-verify"
            implementationClass = "com.march7th.hkrpg.buildlogic.I18nVerifyPlugin"
        }
    }
}
