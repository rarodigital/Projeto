package com.raro.controletv.receiver

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Liga o receptor sozinho quando o TV Box reinicia, sem precisar abrir o app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a == Intent.ACTION_BOOT_COMPLETED ||
            a == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            a == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val remote = Intent(context, ReceiverService::class.java)
            val dlna = Intent(context, NCastDlnaService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(remote)
                context.startForegroundService(dlna)
            } else {
                context.startService(remote)
                context.startService(dlna)
            }
            reconnectBluetoothAudio(context)
        }
    }

    /** Reconecta sozinho na caixa de som já pareada antes (sem precisar entrar em configurações). */
    private fun reconnectBluetoothAudio(context: Context) {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            if (!adapter.isEnabled) return
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try {
                        val audioClasses = setOf(
                            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
                            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
                            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
                            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO
                        )
                        for (device: BluetoothDevice in adapter.bondedDevices) {
                            if (device.bluetoothClass?.deviceClass !in audioClasses) continue
                            try {
                                val m = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                                m.invoke(proxy, device)
                            } catch (_: Exception) {
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { adapter.closeProfileProxy(profile, proxy) } catch (_: Exception) {}
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)
        } catch (_: Exception) {
        }
    }
}
