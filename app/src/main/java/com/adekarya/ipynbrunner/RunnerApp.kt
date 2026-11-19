package com.adekarya.ipynbrunner

import android.app.Application
import android.content.Context
import com.adekarya.ipynbrunner.data.NotebookStore
import com.adekarya.ipynbrunner.data.SettingsStore
import com.adekarya.ipynbrunner.data.colab.ColabApi
import com.adekarya.ipynbrunner.data.colab.ColabOAuth
import com.adekarya.ipynbrunner.data.network.DefaultHttpClient
import com.adekarya.ipynbrunner.data.run.RunController

class AppContainer(context: Context) {
    val http = DefaultHttpClient
    val settings = SettingsStore(context)
    val notebookStore = NotebookStore(context)
    val oauth = ColabOAuth(settings, http)
    val colabApi = ColabApi(settings, oauth, http)
    val runController = RunController(context, settings, colabApi, oauth)
}

class RunnerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
