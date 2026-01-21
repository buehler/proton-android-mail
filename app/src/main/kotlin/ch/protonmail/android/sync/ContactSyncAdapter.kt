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
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.SyncResult
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import ch.protonmail.android.mailcontact.domain.model.ContactProperty
import ch.protonmail.android.mailcontact.domain.model.DecryptedContact
import ch.protonmail.android.mailcontact.domain.usecase.ObserveDecryptedContact
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.contact.data.local.db.entity.toContactEntity
import me.proton.core.contact.domain.repository.ContactRepository
import javax.inject.Inject

class ContactSyncAdapterFactory @Inject constructor(
    @ApplicationContext private val appContext: Context,
    // Inject Proton session/token providers here later
    private val contactRepository: ContactRepository,
    private val accountManager: AccountManager,
    private val decryptContact: ObserveDecryptedContact
) {

    fun create(): ContactSyncAdapter =
        ContactSyncAdapter(appContext, true, contactRepository, accountManager, decryptContact)
}

class ContactSyncAdapter(
    context: Context,
    autoInitialize: Boolean,
    private val contactRepository: ContactRepository,
    private val accountManager: AccountManager,
    private val decryptContact: ObserveDecryptedContact
) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    override fun onSecurityException(account: Account?, extras: Bundle?, authority: String?, syncResult: SyncResult?) {
        Log.e("ContactSync", "Error security exception ${account?.name}, $syncResult")
        super.onSecurityException(account, extras, authority, syncResult)
    }

    override fun onSyncCanceled() {
        Log.w("ContactSync", "on sync cancelled")
        super.onSyncCanceled()
    }

    override fun onSyncCanceled(thread: Thread?) {
        Log.w("ContactSync", "onsynccanceled thread $thread")
        super.onSyncCanceled(thread)
    }

    override fun onUnsyncableAccount(): Boolean {
        Log.w("ContactSync", "on unsychable account")
        return super.onUnsyncableAccount()
    }

    private fun rawContactsSyncUri(): Uri =
        ContactsContract.RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .build()

    private fun dataSyncUri(): Uri =
        ContactsContract.Data.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .build()

    private fun settingsSyncUri(): Uri =
        ContactsContract.Settings.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .build()

    private fun groupMembershipMime() = ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE

    private fun ensureUngroupedVisible(
        provider: ContentProviderClient,
        accountName: String,
        accountType: String
    ) {
        val values = ContentValues().apply {
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
        }
        provider.update(
            settingsSyncUri(),
            values,
            "${ContactsContract.Settings.ACCOUNT_NAME}=? AND ${ContactsContract.Settings.ACCOUNT_TYPE}=?",
            arrayOf(accountName, accountType)
        )
    }

    private data class LocalRaw(val rawId: Long, val sourceId: String)

    private fun queryLocalRawContacts(
        provider: ContentProviderClient,
        accountName: String,
        accountType: String
    ): List<LocalRaw> {
        val projection = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.SOURCE_ID
        )
        val selection =
            "${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
                "${ContactsContract.RawContacts.SOURCE_ID} IS NOT NULL"
        val args = arrayOf(accountName, accountType)

        val cursor = provider.query(
            ContactsContract.RawContacts.CONTENT_URI,
            projection,
            selection,
            args,
            null
        ) ?: return emptyList()

        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
            val srcIdx = it.getColumnIndexOrThrow(ContactsContract.RawContacts.SOURCE_ID)
            val out = ArrayList<LocalRaw>()
            while (it.moveToNext()) {
                val rawId = it.getLong(idIdx)
                val src = it.getString(srcIdx) ?: continue
                out.add(LocalRaw(rawId, src))
            }
            return out
        }
    }

    private fun deleteRawByIdOps(rawIds: List<Long>): ArrayList<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>(rawIds.size)
        val rawUri = rawContactsSyncUri()
        for (id in rawIds) {
            ops += ContentProviderOperation.newDelete(rawUri)
                .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(id.toString()))
                .build()
        }
        return ops
    }

    private fun buildDeleteOps(missingLocals: List<LocalRaw>): ArrayList<ContentProviderOperation> {
        val rawUri = rawContactsSyncUri()
        val ops = ArrayList<ContentProviderOperation>(missingLocals.size)

        for (local in missingLocals) {
            ops += ContentProviderOperation.newDelete(rawUri)
                .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(local.rawId.toString()))
                .build()
        }
        return ops
    }

    private fun deleteRawBySourceIdOps(
        accountName: String,
        accountType: String,
        sourceId: String
    ): ContentProviderOperation =
        ContentProviderOperation.newDelete(rawContactsSyncUri())
            .withSelection(
                "${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
                    "${ContactsContract.RawContacts.SOURCE_ID}=?",
                arrayOf(accountName, accountType, sourceId)
            )
            .build()

    private fun deleteDataBySourceIdOps(
        accountName: String,
        accountType: String,
        sourceId: String
    ): ContentProviderOperation =
        ContentProviderOperation.newDelete(dataSyncUri())
            .withSelection(
                "${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
                    "${ContactsContract.RawContacts.SOURCE_ID}=?",
                arrayOf(accountName, accountType, sourceId)
            )
            .build()

    private fun addData(
        accountName: String,
        accountType: String,
        contact: DecryptedContact
    ): ArrayList<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()

        ops += ContentProviderOperation.newInsert(rawContactsSyncUri())
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
            .withValue(ContactsContract.RawContacts.SOURCE_ID, contact.id!!.id)
            .build()

        val backRef = 0

        // name
        run {
            val structured = contact.structuredName
            val formatted = contact.formattedName?.value

            val b = ContentProviderOperation.newInsert(dataSyncUri())
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                )

            // Prefer formatted display name if present
            formatted?.let { b.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, it) }

            structured?.let {
                it.given.let { v -> b.withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, v) }
                it.family.let { v -> b.withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, v) }
            }

            ops += b.build()
        }

        // mails
        for (e in contact.emails) {
            val addr = e.value.trim()
            if (addr.isEmpty()) continue
            ops += ContentProviderOperation.newInsert(dataSyncUri())
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, addr)
                .withValue(
                    ContactsContract.CommonDataKinds.Email.TYPE, when (e.type) {
                        ContactProperty.Email.Type.Home -> ContactsContract.CommonDataKinds.Email.TYPE_HOME
                        ContactProperty.Email.Type.Work -> ContactsContract.CommonDataKinds.Email.TYPE_WORK
                        else -> ContactsContract.CommonDataKinds.Email.TYPE_OTHER
                    }
                )
                .build()
        }

        // Phones
        for (p in contact.telephones) {
            val num = p.text.trim()
            if (num.isEmpty()) continue
            ops += ContentProviderOperation.newInsert(dataSyncUri())
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, num)
                .withValue(
                    ContactsContract.CommonDataKinds.Phone.TYPE, when (p.type) {
                        ContactProperty.Telephone.Type.Telephone -> ContactsContract.CommonDataKinds.Phone.TYPE_MAIN
                        ContactProperty.Telephone.Type.Home -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
                        ContactProperty.Telephone.Type.Work -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                        ContactProperty.Telephone.Type.Other -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                        ContactProperty.Telephone.Type.Mobile -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                        ContactProperty.Telephone.Type.Main -> ContactsContract.CommonDataKinds.Phone.TYPE_MAIN
                        ContactProperty.Telephone.Type.Fax -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER_FAX
                        ContactProperty.Telephone.Type.Pager -> ContactsContract.CommonDataKinds.Phone.TYPE_PAGER
                    }
                )
                .build()
        }

        // Addresses
        for (a in contact.addresses) {
            ops += ContentProviderOperation.newInsert(dataSyncUri())
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE
                )
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.STREET, a.streetAddress)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.CITY, a.locality)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.REGION, a.region)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE, a.postalCode)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY, a.country)
                .withValue(
                    ContactsContract.CommonDataKinds.StructuredPostal.TYPE, when (a.type) {
                        ContactProperty.Address.Type.Home -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME
                        ContactProperty.Address.Type.Work -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK
                        else -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_OTHER
                    }
                )
                .build()
        }

        // Birthday
        contact.birthday?.date?.let { date ->
            ops += ContentProviderOperation.newInsert(dataSyncUri())
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, date.toString())
                .withValue(ContactsContract.CommonDataKinds.Event.TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
                .build()
        }

        // Anniversary
        contact.anniversary?.date?.let { date ->
            ops += ContentProviderOperation.newInsert(dataSyncUri())
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, date.toString())
                .withValue(ContactsContract.CommonDataKinds.Event.TYPE, ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY)
                .build()
        }

        // Notes (can be multiple; Contacts supports multiple note rows)
        for (n in contact.notes) {
            val text = n.value
            if (text.isEmpty()) continue
            ops += ContentProviderOperation.newInsert(dataSyncUri())
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Note.NOTE, text)
                .build()
        }

        for(o in contact.organizations){
            val text = o.value
            if(text.isEmpty()) continue
            ops += ContentProviderOperation.newInsert(dataSyncUri())
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, text)
                .withValue(ContactsContract.CommonDataKinds.Organization.TYPE, ContactsContract.CommonDataKinds.Organization.TYPE_OTHER)
                .build()
        }

        for (t in contact.titles) {
            val title = t.value
            if (title.isEmpty()) continue
            ops += ContentProviderOperation.newInsert(dataSyncUri())
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, title)
                .withValue(ContactsContract.CommonDataKinds.Organization.TYPE, ContactsContract.CommonDataKinds.Organization.TYPE_OTHER)
                .build()
        }

        for (u in contact.urls) {
            val url = u.value
            if (url.isEmpty()) continue
            ops += ContentProviderOperation.newInsert(dataSyncUri())
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Website.URL, url)
                .withValue(ContactsContract.CommonDataKinds.Website.TYPE, ContactsContract.CommonDataKinds.Website.TYPE_HOMEPAGE)
                .build()
        }

        contact.photos.firstOrNull()?.let { pic ->
            ops += ContentProviderOperation.newInsert(dataSyncUri())
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, pic.data)
                .build()
        }


        return ops
    }

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) = runBlocking {
        val protonAccounts = accountManager.getAccounts().firstOrNull()
        if (protonAccounts == null) {
            Log.w("ContactSync", "no proton accounts found!")
            return@runBlocking
        }

        val acc = protonAccounts.firstOrNull { it.email == account.name || it.userId.id == account.name }
        if (acc == null) {
            Log.w("ContactSync", "no account for ${account.name} found!")
            return@runBlocking
        }

        Log.i("ContactSync", "perform sync for proton acc ${acc.email}.")

        val protonContacts = contactRepository.getAllContacts(acc.userId, true).map { contact ->
            decryptContact.invoke(acc.userId, contact.id, true).first { it.isRight() }
        }.mapNotNull { it.getOrNull() }.filter { it.id != null }
        Log.i("ContactSync", "found ${protonContacts.size} contacts on proton")
        val localContacts = queryLocalRawContacts(provider, account.name, account.type)
        Log.i("ContactSync", "found ${localContacts.size} contacts on device")

        val protonIds = protonContacts.mapNotNull { it.id?.id }
        val stale = localContacts.filter { it.sourceId !in protonIds }.map { it.rawId }
        val localIds = localContacts.map { it.sourceId }
        val newContacts = protonContacts.filter { it.id?.id !in localIds }
        val updateContacts = protonContacts.filter { it.id?.id in localIds }

        syncResult.stats.numUpdates += updateContacts.size
        syncResult.stats.numInserts += newContacts.size
        syncResult.stats.numDeletes += stale.size

        if (stale.isNotEmpty()) {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, deleteRawByIdOps(stale))
            Log.i("ContactSync", "Deleted stale raws: ${stale.size}")
        }

        context.contentResolver.applyBatch(
            ContactsContract.AUTHORITY,
            ArrayList(updateContacts.map { deleteRawBySourceIdOps(account.name, account.type, it.id!!.id) })
        )


        for (contact in protonContacts) {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, addData(account.name, account.type, contact))
        }

        Log.i(
            "ContactSync",
            "stats: add ${syncResult.stats.numInserts}, update ${syncResult.stats.numUpdates}, delete ${syncResult.stats.numDeletes}"
        )


//            val ops = ArrayList<ContentProviderOperation>()
//            ops += deleteRawBySourceIdOps(accountName, accountType, protonId)
//            ops += buildInsertOpsForDecrypted(accountName, accountType, protonId, decrypted)
//            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
//            syncResult.stats.numInserts++
    }

////        val cr = context.contentResolver
////        val accountName = account.name
////        val accountType = ContactSyncAccount.ACCOUNT_TYPE
////        val remoteId = "proton_contact_id_1"
////
////        val rawContactsUri = ContactsContract.RawContacts.CONTENT_URI
////            .buildUpon()
////            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
////            .build()
////
////        val dataUri = ContactsContract.Data.CONTENT_URI
////            .buildUpon()
////            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
////            .build()
////
////        val ops = ArrayList<ContentProviderOperation>()
//
////        ops += ContentProviderOperation.newDelete(dataUri).build()
////        ops += ContentProviderOperation.newDelete(rawContactsUri).build()
////        // 1) Insert RawContact
////        ops += ContentProviderOperation.newInsert(rawContactsUri)
////            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
////            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
////            .withValue(ContactsContract.RawContacts.SOURCE_ID, remoteId)
////            .build()
////
////        // 2) StructuredName
////        ops += ContentProviderOperation.newInsert(dataUri)
////            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
////            .withValue(
////                ContactsContract.Data.MIMETYPE,
////                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
////            )
////            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Proton Test Contact")
////            .build()
////
////        // 3) Phone
////        ops += ContentProviderOperation.newInsert(dataUri)
////            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
////            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
////            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, "+41 79 000 00 00")
////            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
////            .build()
////
////        cr.applyBatch(ContactsContract.AUTHORITY, ops)
//    }
}