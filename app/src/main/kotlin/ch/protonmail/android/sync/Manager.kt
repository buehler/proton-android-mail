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

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log

fun ensureContactSyncAccount(context: Context, protonId: String): Account {
    val am = AccountManager.get(context)
    val account = Account(protonId, ContactSyncAccount.ACCOUNT_TYPE)

    val added = am.addAccountExplicitly(account, null, Bundle().apply {
        // Keep it minimal; don't store secrets here for production.
        putString("proton_id", protonId)
    })
    // added == false usually means it already exists
    return account
}

fun enableContactsSync(account: Account) {
    val authority = ContactsContract.AUTHORITY // "com.android.contacts"

    // Mark as syncable
    ContentResolver.setIsSyncable(account, authority, 1)

    // Allow the system to autosync
    ContentResolver.setSyncAutomatically(account, authority, true)

    // Optional: periodic sync (e.g., every 6 hours)
    ContentResolver.addPeriodicSync(
        account,
        authority,
        Bundle.EMPTY,
        1 * 60 * 60L
    )
}

fun requestImmediateSync(account: Account) {
    val extras = Bundle().apply {
        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
    }
    ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras)
}

fun removeContactsSyncAccount(context: Context, account: Account) {
    val authority = ContactsContract.AUTHORITY

    // Stop future sync scheduling
    ContentResolver.setSyncAutomatically(account, authority, false)
    ContentResolver.cancelSync(account, authority)

    // Remove the account (does not delete server-side)
    AccountManager.get(context).removeAccountExplicitly(account)
}
