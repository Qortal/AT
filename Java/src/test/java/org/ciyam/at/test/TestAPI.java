package org.ciyam.at.test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.ciyam.at.API;
import org.ciyam.at.ExecutionException;
import org.ciyam.at.FunctionData;
import org.ciyam.at.IllegalFunctionCodeException;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.ciyam.at.Timestamp;

public class TestAPI extends API {

	/** Average period between blocks, in seconds. */
	public static final int BLOCK_PERIOD = 60;
	/** Maximum number of steps before auto-sleep. */
	public static final int MAX_STEPS_PER_ROUND = 500;
	/** Op-code step multiplier for calling functions. */
	public static final int STEPS_PER_FUNCTION_CALL = 10;

	/** Initial balance for simple test scenarios. */
	public static final long DEFAULT_INITIAL_BALANCE = 10_0000_0000L;
	/** Initial block height for simple test scenarios. */
	public static final int DEFAULT_INITIAL_BLOCK_HEIGHT = 10;
	/** AT creation block height for simple test scenarios. */
	public static final int DEFAULT_AT_CREATION_BLOCK_HEIGHT = 8;

	public static final String AT_CREATOR_ADDRESS = "AT Creator";
	public static final String AT_ADDRESS = "AT";

	private static final Random RANDOM = new Random();

	public static class TestAccount {
		public String address;
		public long balance;
		public List<byte[]> messages = new ArrayList<>();

		public TestAccount(String address, long amount) {
			this.address = address;
			this.balance = amount;
		}

		public void addToMap(Map<String, TestAccount> map) {
			map.put(this.address, this);
		}
	}

	public static class TestTransaction {
		public long timestamp; // block height & sequence
		public byte[] txHash;
		public API.ATTransactionType txType;
		public String sender;
		public String recipient;
		public long amount;
		public byte[] message;

		private TestTransaction(byte[] txHash, API.ATTransactionType txType, String sender, String recipient) {
			this.txHash = txHash;
			this.txType = txType;
			this.sender = sender;
			this.recipient = recipient;
		}

		public TestTransaction(byte[] txHash, String sender, String recipient, long amount) {
			this(txHash, API.ATTransactionType.PAYMENT, sender, recipient);

			this.amount = amount;
		}

		public TestTransaction(byte[] txHash, String sender, String recipient, byte[] message) {
			this(txHash, API.ATTransactionType.MESSAGE, sender, recipient);

			this.message = new byte[32];
			System.arraycopy(message, 0, this.message, 0, message.length);
		}

		public void setTimestamp(long timestamp) {
			this.timestamp = timestamp;
		}
	}

	public static class TestBlock {
		public byte[] blockHash;

		public List<TestTransaction> transactions = new ArrayList<TestTransaction>();

		public TestBlock() {
			this.blockHash = new byte[32];
			RANDOM.nextBytes(this.blockHash);
		}

		public TestBlock(byte[] blockHash) {
			this.blockHash = new byte[32];
			System.arraycopy(this.blockHash, 0, blockHash, 0, blockHash.length);
		}
	}

	public List<TestBlock> blockchain = new ArrayList<>();
	public Map<String, TestAccount> accounts = new HashMap<>();
	public Map<String, TestTransaction> transactions = new HashMap<>();
	public List<TestTransaction> atTransactions = new ArrayList<>();

	private TestBlock currentBlock = new TestBlock();
	private int currentBlockHeight;

	public TestAPI() {
		this.currentBlockHeight = DEFAULT_INITIAL_BLOCK_HEIGHT;

		// Fill block chain from block 1 to initial height with empty blocks
		for (int h = 1; h <= this.currentBlockHeight; ++h)
			blockchain.add(new TestBlock());

		// Set up test accounts
		new TestAccount(AT_CREATOR_ADDRESS, DEFAULT_INITIAL_BALANCE).addToMap(accounts);
		new TestAccount(AT_ADDRESS, DEFAULT_INITIAL_BALANCE).addToMap(accounts);
		new TestAccount("Initiator", DEFAULT_INITIAL_BALANCE * 2).addToMap(accounts);
		new TestAccount("Responder", DEFAULT_INITIAL_BALANCE * 3).addToMap(accounts);
		new TestAccount("Bystander", DEFAULT_INITIAL_BALANCE * 4).addToMap(accounts);
	}

	// Hook to be overridden
	protected boolean willExecute(MachineState state, int blockHeight) {
		return true;
	}

	// Hook to be overridden
	protected void preExecute(MachineState state) {
	}

	public static byte[] encodeAddress(String address) {
		byte[] encodedAddress = new byte[32];
		System.arraycopy(address.getBytes(), 0, encodedAddress, 0, address.length());
		return encodedAddress;
	}

	/**
	 * Returns address based on passed bytes.
	 * <p>
	 * If any byte is non-ASCII-printable, except trailing zeros,
	 * then we assume address needs Base58 encoding.
	 * <p>
	 * Otherwise, assume bytes contain literal address.
	 */
	public static String decodeAddress(byte[] encodedAddress) {
		int lastNonZeroIndex = -1;
		for (int i = encodedAddress.length - 1; i >= 0; --i) {
			byte b = encodedAddress[i];

			if (b == 0 && lastNonZeroIndex == -1)
				continue;

			if (lastNonZeroIndex == -1)
				lastNonZeroIndex = i;

			if (b < 32 || b > 126)
				return Base58.encode(Arrays.copyOf(encodedAddress, lastNonZeroIndex + 1));
		}

		String address = new String(encodedAddress, StandardCharsets.ISO_8859_1);
		return address.replace("\0", "");
	}

	public static String stringifyHash(byte[] hash) {
		return new String(hash, StandardCharsets.ISO_8859_1);
	}

	public void bumpCurrentBlockHeight() {
		++this.currentBlockHeight;
	}

	public void setCurrentBlockHeight(int blockHeight) {
		if (blockHeight > blockchain.size())
			throw new IllegalStateException("Refusing to set current block height to beyond blockchain end");

		this.currentBlockHeight = blockHeight;
	}

	public void addTransactionToCurrentBlock(TestTransaction testTransaction) {
		currentBlock.transactions.add(testTransaction);
	}

	public void addCurrentBlockToChain() {
		addBlockToChain(currentBlock);
		currentBlock = new TestBlock();
	}

	public TestBlock addBlockToChain(TestBlock newBlock) {
		blockchain.add(newBlock);
		final int blockHeight = blockchain.size();
		StringBuilder sb = new StringBuilder(256);

		for (int seq = 0; seq < newBlock.transactions.size(); ++seq) {
			TestTransaction transaction = newBlock.transactions.get(seq);

			// Set transaction timestamp
			transaction.timestamp = Timestamp.toLong(blockHeight, seq);

			// Add to transactions map
			transactions.put(stringifyHash(transaction.txHash), transaction);

			// Transaction sent/received by AT? Add to AT transactions list
			if (transaction.sender.equals(AT_ADDRESS) || transaction.recipient.equals(AT_ADDRESS))
				atTransactions.add(transaction);

			// Process PAYMENT transactions
			if (transaction.txType == ATTransactionType.PAYMENT) {
				sb.setLength(0);
				sb.append(transaction.sender)
						.append(" sent ")
						.append(prettyAmount(transaction.amount));

				// Subtract amount from sender
				TestAccount senderAccount = accounts.get(transaction.sender);
				if (senderAccount == null)
					throw new IllegalStateException(String.format("Can't send from unknown sender %s: no funds!",
							transaction.sender));

				// Do not apply if sender is AT because balance update already performed during execution
				if (!transaction.sender.equals(AT_ADDRESS)) {
					senderAccount.balance -= transaction.amount;
				}

				if (senderAccount.balance < 0)
					throw new IllegalStateException(String.format("Can't send %s from %s: insufficient funds (%s)",
							prettyAmount(transaction.amount),
							transaction.sender,
							prettyAmount(senderAccount.balance)));

				// Add amount to recipient
				sb.append(" to ");
				TestAccount recipientAccount = accounts.get(transaction.recipient);
				if (recipientAccount == null) {
					sb.append("(new) ");
					recipientAccount = new TestAccount(transaction.recipient, 0);
					accounts.put(transaction.recipient, recipientAccount);
				}
				recipientAccount.balance += transaction.amount;
				sb.append(transaction.recipient);

				System.out.println(sb.toString());
			}
		}

		return newBlock;
	}

	private TestBlock generateBlock(boolean withTransactions, boolean includeTransactionToAt) {
		TestBlock newBlock = new TestBlock();

		if (!withTransactions)
			return newBlock;

		TestAccount atAccount = accounts.get(AT_ADDRESS);
		List<TestAccount> senderAccounts = new ArrayList<>(accounts.values());
		List<TestAccount> recipientAccounts = new ArrayList<>(accounts.values());
		if (!includeTransactionToAt)
			recipientAccounts.remove(atAccount);

		boolean includesAtTransaction = false;
		int transactionCount = 8 + RANDOM.nextInt(8);
		for (int i = 0; i < transactionCount || includeTransactionToAt && !includesAtTransaction; ++i) {
			// Pick random sender
			TestAccount sender = senderAccounts.get(RANDOM.nextInt(senderAccounts.size()));
			// Pick random recipient
			TestAccount recipient = recipientAccounts.get(RANDOM.nextInt(recipientAccounts.size()));
			// Pick random transaction type
			API.ATTransactionType txType = API.ATTransactionType.valueOf(RANDOM.nextInt(2));

			TestTransaction transaction = generateTransaction(sender.address, recipient.address, txType);
			newBlock.transactions.add(transaction);

			if (recipient.address.equals(AT_ADDRESS))
				includesAtTransaction = true;
		}

		return newBlock;
	}

	public TestBlock generateEmptyBlock() {
		return generateBlock(false, false);
	}

	public TestBlock generateBlockWithNonAtTransactions() {
		return generateBlock(true, false);
	}

	public TestBlock generateBlockWithAtTransaction() {
		return generateBlock(true, true);
	}

	public TestTransaction generateTransaction(String sender, String recipient, API.ATTransactionType txType) {
		TestTransaction transaction;

		// Generate tx hash
		byte[] txHash = new byte[32];
		RANDOM.nextBytes(txHash);

		if (txType == API.ATTransactionType.PAYMENT) {
			long amount = RANDOM.nextInt(100); // small amounts
			transaction = new TestTransaction(txHash, sender, recipient, amount);
		} else {
			byte[] message = new byte[32];
			RANDOM.nextBytes(message);
			transaction = new TestTransaction(txHash, sender, recipient, message);
		}

		return transaction;
	}

	@Override
	public int getMaxStepsPerRound() {
		return MAX_STEPS_PER_ROUND;
	}

	@Override
	public int getOpCodeSteps(OpCode opcode) {
		if (opcode.value >= OpCode.EXT_FUN.value && opcode.value <= OpCode.EXT_FUN_RET_DAT_2.value)
			return STEPS_PER_FUNCTION_CALL;

		return 1;
	}

	@Override
	public long getFeePerStep() {
		return 1L;
	}

	@Override
	public int getCurrentBlockHeight() {
		return this.currentBlockHeight;
	}

	@Override
	public int getATCreationBlockHeight(MachineState state) {
		return DEFAULT_AT_CREATION_BLOCK_HEIGHT;
	}

	@Override
	public void putPreviousBlockHashIntoA(MachineState state) {
		int previousBlockHeight = this.currentBlockHeight - 1;
		this.setA(state, blockchain.get(previousBlockHeight - 1).blockHash);
	}

	@Override
	public void putTransactionAfterTimestampIntoA(Timestamp timestamp, MachineState state) {
		int blockHeight = timestamp.blockHeight;
		int transactionSequence = timestamp.transactionSequence + 1;

		while (blockHeight <= this.currentBlockHeight) {
			TestBlock block = this.blockchain.get(blockHeight - 1);

			List<TestTransaction> transactions = block.transactions;

			if (transactionSequence >= transactions.size()) {
				// No more transactions at this height
				++blockHeight;
				transactionSequence = 0;
				continue;
			}

			TestTransaction transaction = transactions.get(transactionSequence);

			if (transaction.recipient.equals("AT")) {
				// Found a transaction
				System.out.println(String.format("Found transaction at height %d, sequence %d: %s %s from %s",
						blockHeight,
						transactionSequence,
						transaction.txType.equals(ATTransactionType.PAYMENT) ? prettyAmount(transaction.amount) : "",
						transaction.txType.name(),
						transaction.sender
				));

				// Generate pseudo-hash of transaction
				this.setA(state, transaction.txHash);
				return;
			}

			++transactionSequence;
		}

		// Nothing found
		System.out.println("No more transactions found at height " + this.currentBlockHeight);
		this.setA(state, new byte[32]);
	}

	public TestTransaction getTransactionFromA(MachineState state) {
		byte[] aBytes = this.getA(state);
		String txHashString = stringifyHash(aBytes);
		return transactions.get(txHashString);
	}

	@Override
	public long getTypeFromTransactionInA(MachineState state) {
		TestTransaction transaction = getTransactionFromA(state);
		return transaction.txType.value;
	}

	@Override
	public long getAmountFromTransactionInA(MachineState state) {
		TestTransaction transaction = getTransactionFromA(state);
		if (transaction.txType != API.ATTransactionType.PAYMENT)
			return 0L;

		return transaction.amount;
	}

	@Override
	public long getTimestampFromTransactionInA(MachineState state) {
		TestTransaction transaction = getTransactionFromA(state);
		return transaction.timestamp;
	}

	@Override
	public long generateRandomUsingTransactionInA(MachineState state) {
		if (!isFirstOpCodeAfterSleeping(state)) {
			// First call
			System.out.println("generateRandomUsingTransactionInA: first call - sleeping");

			// first-call initialization would go here

			this.setIsSleeping(state, true);

			return 0L; // not used
		} else {
			// Second call
			System.out.println("generateRandomUsingTransactionInA: second call - returning random");

			// HASH(A and new block hash)
			return (this.getA1(state) ^ 9L) << 3 ^ (this.getA2(state) ^ 9L) << 12 ^ (this.getA3(state) ^ 9L) << 5 ^ (this.getA4(state) ^ 9L);
		}
	}

	@Override
	public void putMessageFromTransactionInAIntoB(MachineState state) {
		TestTransaction transaction = getTransactionFromA(state);
		if (transaction.txType != API.ATTransactionType.MESSAGE)
			return;

		this.setB(state, transaction.message);
	}

	@Override
	public void putAddressFromTransactionInAIntoB(MachineState state) {
		TestTransaction transaction = getTransactionFromA(state);
		byte[] bBytes = encodeAddress(transaction.sender);
		this.setB(state, bBytes);
	}

	@Override
	public void putCreatorAddressIntoB(MachineState state) {
		byte[] bBytes = encodeAddress(AT_CREATOR_ADDRESS);
		this.setB(state, bBytes);
	}

	@Override
	public long getCurrentBalance(MachineState state) {
		return this.accounts.get(AT_ADDRESS).balance;
	}

	// Debugging only
	public void setCurrentBalance(long currentBalance) {
		this.accounts.get(AT_ADDRESS).balance = currentBalance;
		System.out.println(String.format("New AT balance: %s", prettyAmount(currentBalance)));
	}

	@Override
	public void payAmountToB(long amount, MachineState state) {
		byte[] bBytes = this.getB(state);
		String address = decodeAddress(bBytes);

		TestAccount recipient = accounts.get(address);
		if (recipient == null)
			throw new IllegalStateException("Refusing to pay to unknown account: " + address);

		if (amount < 0)
			throw new IllegalStateException(String.format("Refusing to pay negative amount: %s", amount));

		if (amount == 0) {
			System.out.println(String.format("Skipping zero-amount payment to account %s", address));
			return;
		}

		System.out.println(String.format("Creating PAYMENT of %s to %s", prettyAmount(amount), recipient.address));

		final long previousBalance = state.getCurrentBalance();
		final long newBalance = previousBalance - amount;
		System.out.println(String.format("AT current balance was %s, now: %s", prettyAmount(previousBalance), prettyAmount(newBalance)));

		// Add suitable transaction to currentBlock

		// Generate tx hash
		byte[] txHash = new byte[32];
		RANDOM.nextBytes(txHash);

		TestTransaction testTransaction = new TestTransaction(txHash, AT_ADDRESS, recipient.address, amount);
		addTransactionToCurrentBlock(testTransaction);
	}

	@Override
	public void messageAToB(MachineState state) {
		byte[] bBytes = this.getB(state);
		String address = decodeAddress(bBytes);

		TestAccount recipient = accounts.get(address);
		if (recipient == null)
			throw new IllegalStateException("Refusing to send message to unknown account: " + address);

		byte[] message = this.getA(state);
		recipient.messages.add(message);

		// Add suitable transaction to currentBlock

		// Generate tx hash
		byte[] txHash = new byte[32];
		RANDOM.nextBytes(txHash);

		TestTransaction testTransaction = new TestTransaction(txHash, AT_ADDRESS, recipient.address, message);
		addTransactionToCurrentBlock(testTransaction);
	}

	@Override
	public long addMinutesToTimestamp(Timestamp timestamp, long minutes, MachineState state) {
		timestamp.blockHeight += ((int) minutes * 60) / BLOCK_PERIOD;
		return timestamp.longValue();
	}

	@Override
	public void onFinished(long amount, MachineState state) {
		System.out.println("Finished - refunding remaining to creator");

		TestAccount atCreatorAccount = accounts.get(AT_CREATOR_ADDRESS);
		System.out.println(String.format("Creating PAYMENT of %s to AT creator %s", prettyAmount(amount), atCreatorAccount.address));

		// Add suitable transaction to currentBlock

		// Generate tx hash
		byte[] txHash = new byte[32];
		RANDOM.nextBytes(txHash);

		TestTransaction testTransaction = new TestTransaction(txHash, AT_ADDRESS, atCreatorAccount.address, amount);
		addTransactionToCurrentBlock(testTransaction);
	}

	@Override
	public void onFatalError(MachineState state, ExecutionException e) {
		System.out.println("Fatal error: " + e.getMessage());
		System.out.println("No error address set - will refund to creator and finish");
	}

	@Override
	public void platformSpecificPreExecuteCheck(int paramCount, boolean returnValueExpected, MachineState state, short rawFunctionCode)
			throws IllegalFunctionCodeException {
		Integer requiredParamCount;
		Boolean returnsValue;

		switch (rawFunctionCode) {
			case 0x0501:
				// take one arg, no return value
				requiredParamCount = 1;
				returnsValue = false;
				break;

			case 0x0502:
				// take no arg, return a value
				requiredParamCount = 0;
				returnsValue = true;
				break;

			default:
				// Unrecognised platform-specific function code
				throw new IllegalFunctionCodeException("Unrecognised platform-specific function code 0x" + String.format("%04x", rawFunctionCode));
		}

		if (requiredParamCount == null || returnsValue == null)
			throw new IllegalFunctionCodeException("Error during platform-specific function pre-execute check");

		if (paramCount != requiredParamCount)
			throw new IllegalFunctionCodeException("Passed paramCount (" + paramCount + ") does not match platform-specific function code 0x"
					+ String.format("%04x", rawFunctionCode) + " required paramCount (" + requiredParamCount + ")");

		if (returnValueExpected != returnsValue)
			throw new IllegalFunctionCodeException("Passed returnValueExpected (" + returnValueExpected + ") does not match platform-specific function code 0x"
					+ String.format("%04x", rawFunctionCode) + " return signature (" + returnsValue + ")");
	}

	@Override
	public void platformSpecificPostCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
		switch (rawFunctionCode) {
			case 0x0501:
				System.out.println("Platform-specific function 0x0501 called with 0x" + String.format("%016x", functionData.value1));
				break;

			case 0x0502:
				System.out.println("Platform-specific function 0x0502 called!");
				functionData.returnValue = 0x0502L;
				break;

			default:
				// Unrecognised platform-specific function code
				throw new IllegalFunctionCodeException("Unrecognised platform-specific function code 0x" + String.format("%04x", rawFunctionCode));
		}
	}

	public static String prettyAmount(long amount) {
		return BigDecimal.valueOf(amount, 8).toPlainString();
	}

}
