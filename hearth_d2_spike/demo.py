"""
Run:  python demo.py
Plays the full D2 story end-to-end with narration. No network, no state
on disk — everything below is real Ed25519 signatures being made and
checked in memory.
"""

from hearth_identity import Device, IdentityCeremony, PeerVerifier


def step(title):
    print(f"\n{'='*64}\n{title}\n{'='*64}")


def ok(text):
    print(f"  [ok] {text}")


def bad(text):
    print(f"  [XX] {text}")


step("1. Identity ceremony: seed -> identity key -> first devices")
ceremony = IdentityCeremony()
paper = ceremony.paper_seed()
phone = Device("wong-phone")
node = Device("wong-homenode")
ceremony.enroll_first_device(phone)
phone.enroll_new_device(node)
ok(f"identity   {ceremony.identity_pub[:16]}…")
ok(f"phone cert {phone.cert.device_pub[:16]}…  (signed by identity)")
ok(f"node cert  {node.cert.device_pub[:16]}…  (enrolled BY THE PHONE)")
ok(f"paper seed in the drawer: {paper[:8]}…{paper[-8:]}")

step("2. In-person QR friend-add — home node deliberately offline")
node_backup_pub = node.device_pub  # remember for later
freja = PeerVerifier("freja")
nonce = freja.fresh_nonce()
qr = phone.make_qr(nonce)
accepted = freja.add_friend_via_qr(qr, nonce)
ok("Freja verified Wong's QR fully offline (cert chain + live nonce)"
   if accepted else "FAILED")
post = phone.sign_message({"kind": "post", "text": "hej Freja"})
ok(f"post seq={post.seq} accepted by Freja"
   if freja.verify_message(post) else "FAILED")

step("3. Phone drowns. Wong walks to his desk.")
dead_seq = phone.current_seq
phone.destroy()
new_phone = Device("wong-phone-2")
node.enroll_new_device(new_phone)
rev = node.revoke_device(qr.cert.device_pub, dead_seq)
freja.process_revocation(rev)
ok(f"home node enrolled replacement + revoked old phone at seq {dead_seq}")
post2 = new_phone.sign_message({"kind": "post", "text": "new phone, same me"})
ok("Freja accepts the new phone with NO re-meeting"
   if freja.verify_message(post2) else "FAILED")

step("4. Thief fishes the old phone out of the canal")
# Fresh victim setup so the stolen device is live (worst case: thief
# holds the unlocked phone with its keys).
print("  thief tries seq beyond revocation and a backdated seq:")
ceremony2 = IdentityCeremony()
victim = Device("victim-phone")
ceremony2.enroll_first_device(victim)
peer = PeerVerifier("peer", track_seqs=True)
n2 = peer.fresh_nonce()
peer.add_friend_via_qr(victim.make_qr(n2), n2)
for i in range(3):
    peer.verify_message(victim.sign_message({"n": i}))
node2 = Device("victim-node")
victim.enroll_new_device(node2)
peer.process_revocation(node2.revoke_device(victim.device_pub, 3))
loot = victim.sign_message({"kind": "post", "text": "send crypto"})
bad("post-revocation signature rejected"
    ) if not peer.verify_message(loot) else ok("!! ACCEPTED — BUG")
back = victim.sign_message_with_seq({"kind": "post", "text": "backdated"}, 2)
bad("backdated signature rejected (seq tracking)"
    ) if not peer.verify_message(back) else ok("!! ACCEPTED — BUG")

step("5. House fire: ALL devices destroyed. Paper seed recovery.")
new_phone.destroy()
node.destroy()
recovered = IdentityCeremony.recover(paper)
assert recovered.identity_pub == ceremony.identity_pub
phoenix = Device("wong-phone-3")
recovered.enroll_first_device(phoenix)
freja.process_revocation(phoenix.revoke_device(new_phone.device_pub,
                                               new_phone.current_seq or 1))
freja.process_revocation(phoenix.revoke_device(node_backup_pub, 1))
post3 = phoenix.sign_message({"kind": "post", "text": "rising from ashes"})
ok("same identity recovered from paper; Freja accepts, no re-meeting"
   if freja.verify_message(post3) else "FAILED")

print("\nDone. Every check above is a real Ed25519 verification.")
