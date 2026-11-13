package org.projectdrive.app.core.convoy

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.projectdrive.app.core.database.ConvoyDatabase
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConvoyModule {
    @Provides @Singleton fun client() = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    @Provides @Singleton fun database(@ApplicationContext context: Context) = Room.databaseBuilder(context, ConvoyDatabase::class.java, "convoy.db").build()
    @Provides fun dao(database: ConvoyDatabase) = database.convoy()
    @Provides fun repository(impl: HttpTripRepository): TripRepository = impl
}
