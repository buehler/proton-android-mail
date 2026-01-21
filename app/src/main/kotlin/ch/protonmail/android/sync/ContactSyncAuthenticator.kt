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

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.content.Context
import android.os.Bundle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ContactSyncAuthenticatorFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    // Add whatever you need later (e.g., Proton session/token providers)
) {
    fun create(): ContactSyncAuthenticator = ContactSyncAuthenticator(context)
}

class ContactSyncAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {

    // Editing properties is not supported
    override fun editProperties(r: AccountAuthenticatorResponse, s: String): Bundle {
        throw UnsupportedOperationException()
    }

    override fun addAccount(
        r: AccountAuthenticatorResponse,
        s: String,
        s2: String,
        strings: Array<String>,
        bundle: Bundle
    ): Bundle? = null

    override fun confirmCredentials(
        r: AccountAuthenticatorResponse,
        account: Account,
        bundle: Bundle
    ): Bundle? = null

    override fun getAuthToken(
        r: AccountAuthenticatorResponse,
        account: Account,
        s: String,
        bundle: Bundle
    ): Bundle {
        throw UnsupportedOperationException()
    }

    override fun getAuthTokenLabel(s: String): String {
        throw UnsupportedOperationException()
    }

    override fun updateCredentials(
        r: AccountAuthenticatorResponse,
        account: Account,
        s: String,
        bundle: Bundle
    ): Bundle {
        throw UnsupportedOperationException()
    }

    override fun hasFeatures(
        r: AccountAuthenticatorResponse,
        account: Account,
        strings: Array<String>
    ): Bundle {
        throw UnsupportedOperationException()
    }
}
