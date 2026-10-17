package social.hotmess.core

import java.time.Instant

/**
 * Someone in a chat room, by lowercase user id, as the room describes them: [name] is "First L." for
 * a stranger and the full name for one of the viewer's friends, and [friend] is the server's word that
 * they're the viewer's friend. Older servers send only ids, so everything else is optional.
 *
 * Someone who left lately is still listed, offline, until [recentUntil] (null while they're in the
 * room); [reachable] then says a notification reaches them (a yellow dot, for friends and to admins).
 */
data class RoomPerson(
    val userId: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val friend: Boolean = false,
    val recentUntil: Instant? = null,
    val reachable: Boolean = false,
) {
    /** They left, and the room still lists them until [recentUntil]. */
    val isRecent: Boolean get() = recentUntil != null
}

/**
 * Who is in a chat room now, by lowercase user id: the roster on joining, then people coming and going.
 * Immutable; each frame makes a new one. What a frame leaves out (an older server's bare ids) keeps
 * whatever was known before, so a name seen once doesn't disappear. People who left lately stay listed
 * (see [RoomPerson.recentUntil]) until [pruned] drops them.
 */
data class RoomPeople(val byId: Map<String, RoomPerson> = emptyMap()) {
    /** Everyone listed: in the room now, and here recently. */
    val ids: Set<String> get() = byId.keys

    /** Everyone in the room now, leaving out who was here recently. */
    val online: Set<String> get() = byId.filterValues { !it.isRecent }.keys

    operator fun get(userId: String): RoomPerson? = byId[userId.lowercase()]

    /**
     * Everyone in the room now: [online], and [people] (who they are) when the server sends them; then
     * [recent], who left lately, unless they're back.
     */
    fun roster(online: Set<String>, people: List<RoomPerson> = emptyList(), recent: List<RoomPerson> = emptyList()): RoomPeople {
        val described = people.associateBy { it.userId.lowercase() }
        val ids = online.map { it.lowercase() }.toSet() + described.keys
        val here = ids.associateWith { id -> merge(byId[id], described[id], id) }
        val gone = recent.filter { it.recentUntil != null && it.userId.lowercase() !in ids }.associate { person ->
            val id = person.userId.lowercase()
            id to merge(byId[id], person, id).copy(recentUntil = person.recentUntil, reachable = person.reachable)
        }
        return RoomPeople(here + gone)
    }

    /** [userId] came in, with their [name] and [avatarUrl] when the frame carries them. */
    fun joined(userId: String, name: String? = null, avatarUrl: String? = null): RoomPeople {
        val id = userId.lowercase()
        val person = merge(byId[id], RoomPerson(id, name, avatarUrl, friend = byId[id]?.friend ?: false), id)
        return RoomPeople(byId + (id to person))
    }

    /**
     * [userId]'s last connection left. With a [recentUntil] still to come (after [now]) the room keeps
     * listing them, offline, or [reachable] by a notification; otherwise they're gone.
     */
    fun left(userId: String, recentUntil: Instant? = null, reachable: Boolean = false, now: Instant = Instant.now()): RoomPeople {
        val id = userId.lowercase()
        if (recentUntil == null || !recentUntil.isAfter(now)) return RoomPeople(byId - id)
        val known = byId[id] ?: RoomPerson(id)
        return RoomPeople(byId + (id to known.copy(recentUntil = recentUntil, reachable = reachable)))
    }

    /** Drops whoever was here recently but whose time on the list is up at [now]. */
    fun pruned(now: Instant = Instant.now()): RoomPeople {
        val kept = byId.filterValues { person -> person.recentUntil?.isAfter(now) ?: true }
        return if (kept.size == byId.size) this else RoomPeople(kept)
    }

    /** Fills in a name or photo for someone already here (from a chat line they sent), without overriding the room's. */
    fun described(userId: String, name: String?, avatarUrl: String?): RoomPeople {
        val id = userId.lowercase()
        val known = byId[id] ?: return this
        val filled = known.copy(name = known.name ?: name?.takeIf { it.isNotBlank() }, avatarUrl = known.avatarUrl ?: avatarUrl)
        return if (filled == known) this else RoomPeople(byId + (id to filled))
    }

    private fun merge(old: RoomPerson?, new: RoomPerson?, id: String) = RoomPerson(
        userId = id,
        name = new?.name?.takeIf { it.isNotBlank() } ?: old?.name,
        avatarUrl = new?.avatarUrl?.takeIf { it.isNotBlank() } ?: old?.avatarUrl,
        friend = new?.friend ?: old?.friend ?: false,
    )

    companion object {
        /**
         * The "Here now" strip's people: everyone but [viewerId], those in the room before those here
         * recently (the latest to leave first); then friends first then everyone else, each by name
         * (case-insensitive; nameless last, then by id). Someone is a friend when the room says so or
         * [fullName] knows them (the viewer's FriendDirectory), whose full name then wins over the room's.
         */
        fun hereNow(people: Collection<RoomPerson>, viewerId: String?, fullName: (String) -> String? = { null }): List<RoomPerson> =
            people
                .filterNot { RecordId.same(it.userId, viewerId) }
                .map { person ->
                    val full = fullName(person.userId)
                    if (full == null) person else person.copy(name = full, friend = true)
                }
                .sortedWith(
                    compareBy<RoomPerson> { it.isRecent }
                        .thenByDescending { it.recentUntil }
                        .thenBy { !it.friend }
                        .thenBy { it.name == null }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() }
                        .thenBy { it.userId },
                )
    }
}
