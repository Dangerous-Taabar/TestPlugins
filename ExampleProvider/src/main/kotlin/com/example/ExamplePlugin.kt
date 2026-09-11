package com.example.ExamplePlugin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DesiTvSerialzPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DesiTvSerialzProvider())
    }
}
