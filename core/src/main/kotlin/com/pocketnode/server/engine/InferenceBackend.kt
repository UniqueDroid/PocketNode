package com.pocketnode.server.engine

import kotlinx.coroutines.flow.Flow

interface InferenceBackend {
    val name: String
    val supported: Set<Accelerator>

    /** Lädt das Modell. Ist [preferred] nicht nutzbar, fällt das Backend zurück und meldet den echten Wert. */
    suspend fun load(model: ModelFile, preferred: Accelerator, cpuThreads: Int): LoadedModel

    /** Kalter Flow. Endet bei Erfolg mit genau einem [GenerationEvent.Done]. Abbruch stoppt die Generierung. */
    fun generate(request: GenerationRequest): Flow<GenerationEvent>

    suspend fun unload()
}
