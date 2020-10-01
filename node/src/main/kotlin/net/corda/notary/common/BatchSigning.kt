package net.corda.notary.common

import net.corda.core.crypto.Crypto
import net.corda.core.crypto.DigestService
import net.corda.core.crypto.MerkleTree
import net.corda.core.crypto.PartialMerkleTree
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.SignableData
import net.corda.core.crypto.SignatureMetadata
import net.corda.core.crypto.TransactionSignature
import net.corda.core.crypto.sha256
import net.corda.core.flows.NotaryError
import net.corda.core.node.ServiceHub
import java.security.PublicKey

typealias BatchSigningFunction = (Iterable<SecureHash>) -> BatchSignature

/** Generates a signature over the bach of [txIds]. */
fun signBatch(
        txIds: Iterable<SecureHash>,
        notaryIdentityKey: PublicKey,
        services: ServiceHub
): BatchSignature {
    val algorithms = txIds.mapTo(HashSet(), SecureHash::algorithm)
    require(algorithms.size > 0) {
        "Cannot sign an empty batch"
    }
    require(algorithms.size == 1) {
        "Cannot sign a batch with multiple hash algorithms: $algorithms"
    }
    val digestService = DigestService(algorithms.first())
    val merkleTree = MerkleTree.getMerkleTree(txIds.map { digestService.hash(it.bytes) }, digestService)
    val merkleTreeRoot = merkleTree.hash
    val signableData = SignableData(
            merkleTreeRoot,
            SignatureMetadata(
                    services.myInfo.platformVersion,
                    Crypto.findSignatureScheme(notaryIdentityKey).schemeNumberID
            )
    )
    val sig = services.keyManagementService.sign(signableData, notaryIdentityKey)
    return BatchSignature(sig, merkleTree)
}

/** The outcome of just committing a transaction. */
sealed class InternalResult {
    object Success : InternalResult()
    data class Failure(val error: NotaryError) : InternalResult()
}

data class BatchSignature(
        val rootSignature: TransactionSignature,
        val fullMerkleTree: MerkleTree) {
    /** Extracts a signature with a partial Merkle tree for the specified leaf in the batch signature. */
    fun forParticipant(txId: SecureHash): TransactionSignature {
        require(fullMerkleTree.hash.algorithm == txId.algorithm) {
            "The leaf hash algorithm does not match the Merkle tree hash algorithm"
        }
        val digestService = DigestService(txId.algorithm)
        return TransactionSignature(
                rootSignature.bytes,
                rootSignature.by,
                rootSignature.signatureMetadata,
                PartialMerkleTree.build(fullMerkleTree, listOf(digestService.hash(txId.bytes)))
        )
    }
}