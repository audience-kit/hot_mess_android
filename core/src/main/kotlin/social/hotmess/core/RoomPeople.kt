package social.hotmess.core

/**
 * Someone in a chat room, by lowercase user id, as the room describes them: [name] is "First L." for
 * a stranger and the full name for one of the viewer's friends, and [friend] is the server's word that
 * they're the viewer's friend. Older servers send only ids, so everything else is optional.
 */
data class RoomPerson(
    val userId: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val friend: Boolean = false,
)

/**
 * Who is in a chat room now, by lowercase user id: the roster on joining, then people coming and going.
 * Immutable; each frame makes a new one. What a frame leaves out (an older server's bare ids) keeps
 * whatever was known before, so a name seen once doesn't disappear.
 */
data class RoomPeople(val byId: Map<String, RoomPerson> = emptyMap()) {
    val ids: Set<String> get() = byId.keys

    operator fun get(userId: String): RoomPerson? = byId[userId.lowercase()]

    /** Everyone in the room now: [online], and [people] (who they are) when the server sends them. */
    fun roster(online: Set<String>, people: List<RoomPerson> = emptyList()): RoomPeople {
        val described = people.associateBy { it.userId.lowercase() }
        val ids = online.map { it.lowercase() }.toSet() + described.keys
        return RoomPeople(ids.associateWith { id -> merge(byId[id], described[id], id) })
    }

    /** [userId] came in, with their [name] and [avatarUrl] when the frame carries them. */
    fun joined(userId: String, name: String? = null, avatarUrl: String? = null): RoomPeople {
        val id = userId.lowercase()
        val person = merge(byId[id], RoomPerson(id, name, avatarUrl, friend = byId[id]?.friend ?: false), id)
        return RoomPeople(byId + (id to person))
    }

    fun left(userId: String): RoomPeople = RoomPeople(byId - userId.lowercase())

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
         * The "Here now" strip's people: everyone but [viewerId], friends first then everyone else, each
         * by name (case-insensitive; nameless last, then by id). Someone is a friend when the room says so
         * or [fullName] knows them (the viewer's FriendDirectory), whose full name then wins over the room's.
         */
        fun hereNow(people: Collection<RoomPerson>, viewerId: String?, fullName: (String) -> String? = { null }): List<RoomPerson> =
            people
                .filterNot { RecordId.same(it.userId, viewerId) }
                .map { person ->
                    val full = fullName(person.userId)
                    if (full == null) person else person.copy(name = full, friend = true)
                }
                .sortedWith(
                    compareBy<RoomPerson> { !it.friend }
                        .thenBy { it.name == null }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() }
                        .thenBy { it.userId },
                )
    }
}
