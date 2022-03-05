package mindustry.client.antigrief

import arc.*
import arc.util.*
import mindustry.*
import mindustry.client.ClientVars.*
import mindustry.client.utils.*
import mindustry.game.*
import mindustry.gen.*
import mindustry.net.*
import mindustry.ui.*
import java.util.concurrent.*

// FINISHME: Heavily work in progress mod logs
class Moderation {
    private val traces = CopyOnWriteArrayList<Player>() // last people to leave

    init {
        Events.on(EventType.PlayerLeave::class.java) { e ->
            e.player ?: return@on
            e.player.trace ?: return@on

//            traces.forEach { p -> if (p.trace.uuid == e.player.trace.uuid || p.trace.ip == e.player.trace.ip) traces.remove(p) } FINISHME: Remove dupe traces and add the relevant info to the new trace
            while (traces.size >= Core.settings.getInt("leavecount")) traces.removeFirst() // Keep 100 latest leaves
            traces.add(e.player)
        }

        Events.on(EventType.PlayerJoin::class.java) { e -> // Trace players when they join, also traces all players on join
            if (!Vars.player.admin || e.player == null || e.player == Vars.player || e.player.admin || !Core.settings.getBool("modenabled")) return@on

            silentTrace++
            Call.adminRequest(e.player, Packets.AdminAction.trace)
        }
    }

    fun addInfo(player: Player, info: Administration.TraceInfo) {
        // FINISHME: Integrate these with join/leave messages
        if (Time.timeSinceMillis(lastJoinTime) > 10000 && player.trace == null) {
            if (info.timesJoined > 10 && info.timesKicked < 3) Vars.player.sendMessage("[accent]${player.name}[accent] has joined ${info.timesJoined-1} times before, they have been kicked ${info.timesKicked} times")
            else sendMessage("/a [scarlet]${player.name}[scarlet] has joined ${info.timesJoined-1} times before, they have been kicked ${info.timesKicked} times")
        }

        for (n in traces.size - 1 downTo 0) {
            val i = traces[n]
            if (i.trace.ip == info.uuid || i.trace.ip == info.ip) { // Update info
                if (i.trace.uuid != info.uuid) Vars.player.sendMessage("[scarlet]${player.name}[scarlet] has changed UUID: ${i.trace.uuid} -> ${info.uuid}")
                if (i.trace.ip != info.ip) Vars.player.sendMessage("[scarlet]${player.name}[scarlet] has changed IP: ${i.trace.ip} -> ${info.ip}")
                if (i.name != player.name) Vars.player.sendMessage("[scarlet]${player.name}[scarlet] has changed name, was previously: ${i.name}")
            }
        }

        player.trace = info
    }

    fun leftList() {
        dialog("Leaves, newest first") {
            cont.pane {
                for (i in traces.size - 1 downTo 0) {
                    val player = traces[i]
                    it.button(player.name, Styles.nonet) { Vars.ui.traces.show(player, player.trace) }.wrapLabel(false).minWidth(100f)
                    it.row()
                }
            }.growY()
            addCloseButton()
        }.show()
    }
}