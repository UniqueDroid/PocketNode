package com.pocketnode.server.engine

/** Die installierten Modelle. Die Android-App liefert in Stufe 2 eine Variante, die den Modellordner liest. */
interface ModelCatalog {
    fun list(): List<ModelFile>
    fun find(id: String): ModelFile?
}

class InMemoryModelCatalog(private val models: List<ModelFile>) : ModelCatalog {
    override fun list(): List<ModelFile> = models
    override fun find(id: String): ModelFile? = models.firstOrNull { it.id == id }
}
