package social.hotmess.core

import java.util.concurrent.ConcurrentHashMap

/**
 * The signed-in user's friends that the app has seen, by user id, so chat can show them by their full
 * names. A room's frames only carry the short form ("Aurora B.") so a full name never reaches a
 * stranger's phone; the app swaps in the full name for people on the viewer's friend list.
 *
 * There is no query for the whole friend list, so it fills from what Now and the venue screen report:
 * friends out at the venue or in the locale, and at the venues friends have been lately. Those are the
 * friends a room is likely to hold.
 */
class FriendDirectory {
    private val names = ConcurrentHashMap<String, String>()

    fun record(friends: Iterable<Friend>) {
        friends.forEach { friend ->
            val name = friend.name.trim()
            if (name.isNotEmpty()) names[key(friend.id)] = name
        }
    }

    /** Records the friends Now reported. */
    fun record(now: Now) {
        record(now.friends)
        record(now.friendVenues.flatMap { it.friends })
    }

    /** The friend's full name, or null when [userId] isn't a friend the app has seen. */
    fun fullName(userId: String?): String? = userId?.let { names[key(it)] }

    /** The name chat shows for [userId]: a friend's full name, otherwise [shown] as the room sent it. */
    fun displayName(userId: String?, shown: String?): String? = fullName(userId) ?: shown

    fun clear() = names.clear()

    private fun key(id: String): String = RecordId.normalize(id) ?: id.lowercase()
}
