package expo.modules.tormanager

/** Per-device accepted-sequence set, compactable — mirrors
 *  hearth.identity.SeenSet: seqs 1..contiguous are seen, plus a sparse set
 *  above. Accept any UNSEEN seq, reject reuse (D2 Ambush 2). */
class SeenSet(var contiguous: Int = 0, val sparse: MutableSet<Int> = mutableSetOf()) {
    fun has(seq: Int): Boolean = (seq in 1..contiguous) || seq in sparse

    fun add(seq: Int): Boolean {
        if (seq < 1 || has(seq)) return false
        sparse.add(seq)
        while ((contiguous + 1) in sparse) {
            contiguous += 1
            sparse.remove(contiguous)
        }
        return true
    }

    fun toJson(): Map<String, Any> = mapOf("contiguous" to contiguous, "sparse" to sparse.sorted())

    companion object {
        fun fromJson(m: Map<String, Any?>): SeenSet {
            val c = (m["contiguous"] as Number).toInt()
            @Suppress("UNCHECKED_CAST")
            val sp = (m["sparse"] as List<Number>).map { it.toInt() }.toMutableSet()
            return SeenSet(c, sp)
        }
    }
}
