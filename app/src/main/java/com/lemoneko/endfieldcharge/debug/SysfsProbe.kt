package com.lemoneko.endfieldcharge.debug

import com.lemoneko.endfieldcharge.core.BatterySnapshot
import java.io.File

/**
 * Dumps the raw battery sysfs nodes the HUD's numbers are derived from.
 *
 * The displayed mWh is `charge_full * level * nominalVolt`, not a direct reading, because Android
 * exposes no remaining-energy property. This probe exists so the assumption can be checked against
 * the hardware: `charge_counter` and `charge_full` are supposed to be in uAh, but on the K60 they
 * differ by exactly 1000x at 100 %, which suggests `charge_counter` is really in mAh. Until a
 * discharge test settles it, the raw values are kept visible rather than guessed at.
 */
object SysfsProbe {

    private const val DIR = "/sys/class/power_supply/battery"

    private val NODES = listOf(
        "capacity",
        "charge_full",
        "charge_full_design",
        "charge_counter",
        "voltage_now",
        "voltage_ocv",
        "current_now",
        "power_now",
        "cycle_count",
        "temp",
        "status",
    )

    fun report(): String {
        val builder = StringBuilder("sysfs $DIR\n")
        for (node in NODES) {
            builder.append(node.padEnd(20))
            builder.append(read(node) ?: "-")
            builder.append('\n')
        }

        val full = read("charge_full")?.toLongOrNull()
        val counter = read("charge_counter")?.toLongOrNull()
        val voltageUv = read("voltage_now")?.toLongOrNull()
        val capacity = read("capacity")?.toIntOrNull()

        builder.append('\n')
        if (full != null && capacity != null) {
            val snapshot = BatterySnapshot(
                level = capacity,
                plugged = 0,
                status = 0,
                voltageUv = voltageUv ?: -1,
                chargeFullUah = full,
                chargeCounterRaw = counter ?: -1,
            )
            builder.append("derived full      ${snapshot.fullMwh.toInt()} mWh\n")
            builder.append("derived remaining ${snapshot.remainingMwh.toInt()} mWh\n")
            builder.append("nominal volt      ${BatterySnapshot.DEFAULT_NOMINAL_VOLT} V\n")
        }
        if (full != null && counter != null) {
            builder.append("\ncharge_full / charge_counter = ${"%.1f".format(full.toDouble() / counter.toDouble())}\n")
            builder.append("(1000 would mean charge_counter is mAh, 1 would mean uAh)\n")
        }
        return builder.toString()
    }

    private fun read(node: String): String? =
        runCatching { File(DIR, node).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
}
