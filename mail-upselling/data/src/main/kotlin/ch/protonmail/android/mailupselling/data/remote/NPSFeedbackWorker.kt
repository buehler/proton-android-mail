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

package ch.protonmail.android.mailupselling.data.remote

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.protonmail.android.mailcommon.data.worker.Enqueuer
import ch.protonmail.android.mailcommon.domain.util.requireNotBlank
import ch.protonmail.android.mailupselling.data.datasource.NPSFeedbackRemoteDataSource
import ch.protonmail.android.mailupselling.data.remote.resource.NPSFeedbackBody
import ch.protonmail.android.mailupselling.domain.repository.InstalledProtonApps
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.Serializable
import me.proton.core.domain.entity.UserId
import me.proton.core.util.kotlin.deserialize
import me.proton.core.util.kotlin.serialize
import timber.log.Timber

@HiltWorker
class NPSFeedbackWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val dataSource: NPSFeedbackRemoteDataSource
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        val userId = UserId(requireNotBlank(inputData.getString(Keys.UserId), fieldName = "User id"))

        val input = requireNotBlank(inputData.getString(Keys.WorkerInput))
            .deserialize<WorkerInput>()

        val body = NPSFeedbackBody(
            ratingValue = input.ratingValue,
            comment = input.comment,
            installedApps = input.installedProtonApps.map {
                "${it.packagaName}:${it.versionName}"
            }
        )
        return if (input.skipped) {
            dataSource.skip(userId, body)
        } else {
            dataSource.submit(userId, body)
        }.fold(
            ifLeft = {
                Timber
                    .tag("NPSFeedbackWorker")
                    .e("API error submitting feedback - error: %s", it)

                Result.failure()
            },
            ifRight = {
                Result.success()
            }
        )
    }

    private object Keys {
        const val UserId = "UserId"
        const val WorkerInput = "WorkerInput"
    }

    @Serializable
    private data class WorkerInput(
        val userId: UserId,
        val ratingValue: Int?,
        val comment: String?,
        val skipped: Boolean,
        val installedProtonApps: List<InstalledAppInfo>
    ) {
        @Serializable
        data class InstalledAppInfo(val packagaName: String, val versionName: String)
    }

    companion object {

        @Suppress("LongParameterList")
        fun enqueue(
            enqueuer: Enqueuer,
            userId: UserId,
            ratingValue: Int?,
            comment: String?,
            skipped: Boolean,
            installedProtonApps: InstalledProtonApps
        ) {
            enqueuer.enqueue<NPSFeedbackWorker>(
                userId,
                mapOf(
                    Keys.UserId to userId.id,
                    Keys.WorkerInput to WorkerInput(
                        userId = userId,
                        ratingValue = ratingValue,
                        comment = comment,
                        skipped = skipped,
                        installedProtonApps = installedProtonApps.appsAndVersions.map {
                            WorkerInput.InstalledAppInfo(
                                packagaName = it.packageName,
                                versionName = it.version
                            )
                        }
                    ).serialize()
                )
            )
        }
    }
}
