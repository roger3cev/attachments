package net.corda.examples.attachments.tests.flow

import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.examples.attachments.flow.AgreeFlow
import net.corda.examples.attachments.flow.ProposeFlow
import net.corda.examples.attachments.BLACKLIST_JAR_PATH
import net.corda.examples.attachments.tests.INCORRECT_JAR_PATH
import net.corda.examples.attachments.state.AgreementState
import net.corda.node.internal.StartedNode
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetwork.MockNode
import net.corda.testing.setCordappPackages
import net.corda.testing.unsetCordappPackages
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class FlowTests {
    private lateinit var network: MockNetwork
    private lateinit var a: StartedNode<MockNode>
    private lateinit var b: StartedNode<MockNode>
    private lateinit var aIdentity: Party
    private lateinit var bIdentity: Party
    private lateinit var agreementTxt: String
    private lateinit var blacklistAttachment: SecureHash
    private lateinit var incorrectAttachment: SecureHash

    @Before
    fun setup() {
        setCordappPackages("net.corda.examples.attachments")

        network = MockNetwork()

        val nodes = network.createSomeNodes(2)
        a = nodes.partyNodes[0]
        b = nodes.partyNodes[1]
        aIdentity = a.info.legalIdentities.first()
        bIdentity = b.info.legalIdentities.first()

        agreementTxt = "${aIdentity.name} agrees with ${bIdentity.name} that..."

        // We upload the valid attachment to the first node, who will propagate it to the other node as part of the
        // flow.
        val attachmentInputStream = File(BLACKLIST_JAR_PATH).inputStream()
        a.database.transaction {
            blacklistAttachment = a.attachments.importAttachment(attachmentInputStream)
        }

        // We upload the valid attachment to the first node, who will propagate it to the other node as part of the
        // flow.
        val incorrectAttachmentInputStream = File(INCORRECT_JAR_PATH).inputStream()
        a.database.transaction {
            incorrectAttachment = a.attachments.importAttachment(incorrectAttachmentInputStream)
        }

        b.registerInitiatedFlow(AgreeFlow::class.java)

        network.runNetwork()
    }

    @After
    fun tearDown() {
        network.stopNodes()
        unsetCordappPackages()
    }

    @Test
    fun `flow rejects attachments that do not meet the constraints of AttachmentContract`() {
        val flow = ProposeFlow(agreementTxt, incorrectAttachment, bIdentity)
        val future = a.services.startFlow(flow).resultFuture
        network.runNetwork()
        assertFailsWith<TransactionVerificationException> { future.getOrThrow() }
    }

    @Test
    fun `flow records the correct transaction in both parties' transaction storages`() {
        val signedTx = reachAgreement()

        // We check the recorded transaction in both transaction storages.
        listOf(a, b).forEach { node ->
            val recordedTx = node.services.validatedTransactions.getTransaction(signedTx.id)
            assertNotNull(recordedTx)

            // Checks on the signatures.
            recordedTx!!.verifyRequiredSignatures()

            // Checks on the inputs.
            assertEquals(0, recordedTx.inputs.size)

            // Checks on the outputs.
            val outputs = recordedTx.tx.outputs
            assertEquals(1, outputs.size)
            val recordedState = outputs.single().data as AgreementState

            assertEquals(aIdentity, recordedState.partyA)
            assertEquals(bIdentity, recordedState.partyB)
            assertEquals(agreementTxt, recordedState.txt)

            // Checks on the attachments.
            val attachments = recordedTx.tx.attachments
            assertEquals(1, attachments.size)
            assertEquals(blacklistAttachment, attachments.single())
        }
    }

    @Test
    fun `flow records the correct agreement in both parties' vaults`() {
        reachAgreement()

        // We check the recorded agreement in both vaults.
        listOf(a, b).forEach { node ->
            node.database.transaction {
                val agreements = node.services.vaultService.queryBy<AgreementState>().states
                assertEquals(1, agreements.size)

                val recordedState = agreements.single().state.data
                assertEquals(aIdentity, recordedState.partyA)
                assertEquals(bIdentity, recordedState.partyB)
                assertEquals(agreementTxt, recordedState.txt)
            }
        }
    }

    @Test
    fun `flow propagates the attachment to B's attachment storage`() {
        reachAgreement()

        // We check the recorded agreement in both attachment storages.
        b.database.transaction {
            val blacklist = b.services.attachments.openAttachment(blacklistAttachment)
            assertNotNull(blacklist)
        }
    }

    // Uses the propose flow to record an agreement on the ledger.
    private fun reachAgreement(): SignedTransaction {
        val flow = ProposeFlow(agreementTxt, blacklistAttachment, bIdentity)
        val future = a.services.startFlow(flow).resultFuture
        network.runNetwork()
        return future.getOrThrow()
    }
}