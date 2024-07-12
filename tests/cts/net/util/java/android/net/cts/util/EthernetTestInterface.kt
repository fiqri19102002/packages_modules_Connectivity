/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.cts.util

import android.Manifest.permission.NETWORK_SETTINGS
import android.content.Context
import android.net.EthernetManager
import android.net.EthernetManager.InterfaceStateListener
import android.net.EthernetManager.STATE_ABSENT
import android.net.EthernetManager.STATE_LINK_UP
import android.net.IpConfiguration
import android.net.TestNetworkInterface
import android.net.cts.util.EthernetTestInterface.EthernetStateListener.CallbackEntry.InterfaceStateChanged
import android.os.Handler
import com.android.net.module.util.ArrayTrackRecord
import com.android.testutils.runAsShell
import kotlin.test.assertNotNull

private const val TIMEOUT_MS = 5_000L

/**
 * A class to manage the lifecycle of an ethernet interface.
 *
 * This class encapsulates creating new tun/tap interfaces and registering them with ethernet
 * service.
 */
class EthernetTestInterface(
    private val context: Context,
    private val handler: Handler,
    val testIface: TestNetworkInterface
) {
    private class EthernetStateListener(private val trackedIface: String) : InterfaceStateListener {
        val events = ArrayTrackRecord<CallbackEntry>().newReadHead()

        sealed class CallbackEntry {
            data class InterfaceStateChanged(
                val iface: String,
                val state: Int,
                val role: Int,
                val cfg: IpConfiguration?
            ) : CallbackEntry()
        }

        override fun onInterfaceStateChanged(
            iface: String,
            state: Int,
            role: Int,
            cfg: IpConfiguration?
        ) {
            // filter out callbacks for other interfaces
            if (iface != trackedIface) return
            events.add(InterfaceStateChanged(iface, state, role, cfg))
        }

        fun eventuallyExpect(state: Int) {
            val cb = events.poll(TIMEOUT_MS) { it is InterfaceStateChanged && it.state == state }
            assertNotNull(cb, "Never received state $state. Got: ${events.backtrace()}")
        }
    }

    val name get() = testIface.interfaceName
    private val listener = EthernetStateListener(name)
    private val em = context.getSystemService(EthernetManager::class.java)!!

    init{
        em.addInterfaceStateListener(handler::post, listener)
        runAsShell(NETWORK_SETTINGS) {
            em.setIncludeTestInterfaces(true)
        }
        // Wait for link up to be processed in EthernetManager before returning.
        listener.eventuallyExpect(STATE_LINK_UP)
    }

    fun destroy() {
        // It is possible that the fd was already closed by the test, in which case this is a noop.
        testIface.getFileDescriptor().close()
        listener.eventuallyExpect(STATE_ABSENT)

        // setIncludeTestInterfaces() posts on the handler and does not run synchronously. However,
        // there should be no need for a synchronization mechanism here. If the next test is
        // bringing up its interface, a RTM_NEWLINK will be put on the back of the handler and is
        // guaranteed to be in order with (i.e. after) this call, so there is no race here.
        runAsShell(NETWORK_SETTINGS) {
            em.setIncludeTestInterfaces(false)
        }
        em.removeInterfaceStateListener(listener)
    }
}
