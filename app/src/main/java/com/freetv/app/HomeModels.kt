package com.freetv.app

/** One horizontal row of the Home screen. `total` = how many channels the category has in all (items may be a shorter preview). */
data class Row(
    val id: String,
    val title: String,
    val items: List<Channel>,
    val total: Int
)

/** What the vertical Home list shows: an optional hero banner on top, then rows. */
sealed interface HomeItem {
    data class Hero(val channel: Channel) : HomeItem
    data class RowItem(val row: Row) : HomeItem
}
