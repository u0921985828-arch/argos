package com.eddie.surco.data

import android.content.Context
import com.eddie.surco.R

// Cada clave de [Texts] con su recurso (res/values*/strings_data.xml). Una lista escrita a mano y
// no Resources.getIdentifier: así el compilador y lint ven qué recursos se usan.

/** Los textos de [Texts] desde los recursos (idioma elegido). Null: el de por defecto. */
fun androidText(ctx: Context, key: String): String? {
    val id = when (key) {
        "data_locale" -> R.string.data_locale
        "data_group_people" -> R.string.data_group_people
        "data_group_work" -> R.string.data_group_work
        "data_group_time" -> R.string.data_group_time
        "data_group_rhythm" -> R.string.data_group_rhythm
        "data_group_file" -> R.string.data_group_file
        "data_field_artist" -> R.string.data_field_artist
        "data_field_album_artist" -> R.string.data_field_album_artist
        "data_field_composer" -> R.string.data_field_composer
        "data_field_lyricist" -> R.string.data_field_lyricist
        "data_field_producer" -> R.string.data_field_producer
        "data_field_remixer" -> R.string.data_field_remixer
        "data_field_conductor" -> R.string.data_field_conductor
        "data_field_album" -> R.string.data_field_album
        "data_field_genre" -> R.string.data_field_genre
        "data_field_mood" -> R.string.data_field_mood
        "data_field_grouping" -> R.string.data_field_grouping
        "data_field_label" -> R.string.data_field_label
        "data_field_media" -> R.string.data_field_media
        "data_field_language" -> R.string.data_field_language
        "data_field_country" -> R.string.data_field_country
        "data_field_year" -> R.string.data_field_year
        "data_field_decade" -> R.string.data_field_decade
        "data_field_original_year" -> R.string.data_field_original_year
        "data_field_duration" -> R.string.data_field_duration
        "data_field_bpm" -> R.string.data_field_bpm
        "data_field_key" -> R.string.data_field_key
        "data_field_added" -> R.string.data_field_added
        "data_field_format" -> R.string.data_field_format
        "data_field_quality" -> R.string.data_field_quality
        "data_field_bitrate" -> R.string.data_field_bitrate
        "data_field_sample_rate" -> R.string.data_field_sample_rate
        "data_field_bit_depth" -> R.string.data_field_bit_depth
        "data_field_channels" -> R.string.data_field_channels
        "data_field_encoder" -> R.string.data_field_encoder
        "data_field_folder" -> R.string.data_field_folder
        "data_unknown" -> R.string.data_unknown
        "data_no_year" -> R.string.data_no_year
        "data_quality_hires" -> R.string.data_quality_hires
        "data_quality_lossless" -> R.string.data_quality_lossless
        "data_quality_lossy_high" -> R.string.data_quality_lossy_high
        "data_quality_lossy_low" -> R.string.data_quality_lossy_low
        "data_duration_under_1" -> R.string.data_duration_under_1
        "data_duration_over_20" -> R.string.data_duration_over_20
        "data_bitrate_under_128" -> R.string.data_bitrate_under_128
        "data_bitrate_1000_plus" -> R.string.data_bitrate_1000_plus
        "data_stereo" -> R.string.data_stereo
        "data_mono" -> R.string.data_mono
        "data_note_names" -> R.string.data_note_names
        "data_key_minor" -> R.string.data_key_minor
        "data_key_major" -> R.string.data_key_major
        "data_info_title" -> R.string.data_info_title
        "data_info_subtitle" -> R.string.data_info_subtitle
        "data_info_track" -> R.string.data_info_track
        "data_info_disc" -> R.string.data_info_disc
        "data_n_of_total" -> R.string.data_n_of_total
        "data_info_comment" -> R.string.data_info_comment
        "data_info_catalog" -> R.string.data_info_catalog
        "data_info_rating" -> R.string.data_info_rating
        "data_info_lyrics" -> R.string.data_info_lyrics
        "data_yes" -> R.string.data_yes
        "data_info_codec" -> R.string.data_info_codec
        "data_info_size" -> R.string.data_info_size
        "data_info_rg_track" -> R.string.data_info_rg_track
        "data_info_rg_album" -> R.string.data_info_rg_album
        "data_info_file" -> R.string.data_info_file
        "data_smart_favorites" -> R.string.data_smart_favorites
        "data_smart_most_played" -> R.string.data_smart_most_played
        "data_smart_recent" -> R.string.data_smart_recent
        "data_smart_added" -> R.string.data_smart_added
        "data_smart_never" -> R.string.data_smart_never
        "data_playlist_default" -> R.string.data_playlist_default
        "data_car_for_you" -> R.string.data_car_for_you
        "data_car_lists" -> R.string.data_car_lists
        "data_car_albums" -> R.string.data_car_albums
        "data_car_artists" -> R.string.data_car_artists
        "data_car_shuffle_all" -> R.string.data_car_shuffle_all
        "data_car_by_genre" -> R.string.data_car_by_genre
        "data_car_by_tempo" -> R.string.data_car_by_tempo
        // De audio/PurePcm.kt (recurso en strings_app.xml).
        "app_pcm_float" -> R.string.app_pcm_float
        else -> return null
    }
    return ctx.getString(id)
}

/** Los plurales de [Texts] desde los recursos. Null: el de por defecto. */
fun androidPlural(ctx: Context, key: String, n: Int): String? {
    val id = when (key) {
        "data_songs" -> R.plurals.data_songs
        "data_genres" -> R.plurals.data_genres
        "data_channels_n" -> R.plurals.data_channels_n
        else -> return null
    }
    return ctx.resources.getQuantityString(id, n, n)
}
