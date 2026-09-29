package com.muedsa.snapshot.open

/** 仅加载构建时打包的两份固定文档，不接受用户提供的资源路径。 */
internal data class PublicDocs(val openapi: String, val aiGuide: String) {
    companion object {
        fun load(): PublicDocs = PublicDocs(
            openapi = readBundledDocument("openapi.yaml"),
            aiGuide = readBundledDocument("ai-guide.md"),
        )

        private fun readBundledDocument(name: String): String =
            requireNotNull(PublicDocs::class.java.getResourceAsStream("/public-docs/$name")) {
                "Missing bundled public document: $name"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
