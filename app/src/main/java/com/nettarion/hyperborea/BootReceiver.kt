package com.nettarion.hyperborea

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed — starting service")
            context.startHyperboreaService(HyperboreaService.ACTION_BOOT)
        } else {
            Log.w(TAG, "Unexpected action: ${intent.action}")
        }
    }

    private companion object {
        const val TAG = "Boot"
    }
}
