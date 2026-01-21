/*
 * Copyright (c) 2022 Proton Technologies AG
 * This file is part of Proton Technologies AG and Proton Mail.
 *
 * Proton Mail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Mail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Mail. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.protonmail.android.sync

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ContactSyncService : Service() {

    @Inject
    lateinit var factory: ContactSyncAdapterFactory

    companion object {

        @Volatile
        private var adapter: ContactSyncAdapter? = null
        private val lock = Any()
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("ContactSync", "ContactSyncService onCreate in " + Application.getProcessName())
        if (adapter == null) {
            synchronized(lock) {
                if (adapter == null) adapter = factory.create()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.i("ContactSync", "ContactSyncService onBind: $intent")
        return adapter!!.syncAdapterBinder
    }
}