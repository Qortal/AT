package org.ciyam.at.test;

import java.nio.ByteBuffer;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ciyam.at.MachineState;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;

public abstract class ExecutableTest {

	private static final int DATA_OFFSET = MachineState.HEADER_LENGTH; // code bytes are not present
	private static final int CALL_STACK_OFFSET = DATA_OFFSET + TestUtils.NUM_DATA_PAGES * MachineState.VALUE_SIZE;

	public TestLoggerFactory loggerFactory;
	public TestAPI api;
	public MachineState state;
	public ByteBuffer codeByteBuffer;
	public ByteBuffer dataByteBuffer;
	public ByteBuffer stateByteBuffer;
	public int callStackSize;
	public int userStackOffset;
	public int userStackSize;
	public byte[] packedState;

	@BeforeClass
	public static void beforeClass() {
		Security.insertProviderAt(new BouncyCastleProvider(), 0);
	}

	@Before
	public void beforeTest() {
		loggerFactory = new TestLoggerFactory();
		api = new TestAPI();
		codeByteBuffer = ByteBuffer.allocate(TestUtils.NUM_CODE_PAGES * MachineState.OPCODE_SIZE);
		dataByteBuffer = ByteBuffer.allocate(TestUtils.NUM_DATA_PAGES * MachineState.VALUE_SIZE);
		stateByteBuffer = null;
		packedState = null;
	}

	@After
	public void afterTest() {
		packedState = null;
		stateByteBuffer = null;
		codeByteBuffer = null;
		dataByteBuffer = null;
		api = null;
		loggerFactory = null;
	}

	protected void execute(boolean onceOnly) {
		byte[] headerBytes = TestUtils.HEADER_BYTES;
		byte[] codeBytes = codeByteBuffer.array();
		byte[] dataBytes = dataByteBuffer.array();

		if (packedState == null) {
			// First time
			System.out.println("First execution - deploying...");
			state = new MachineState(api, loggerFactory, headerBytes, codeBytes, dataBytes);
			packedState = state.toBytes();
		}

		do {
			state = MachineState.fromBytes(api, loggerFactory, packedState, codeBytes);

			System.out.println("Starting execution round!");
			System.out.println("Current block height: " + api.getCurrentBlockHeight());
			System.out.println("Previous balance: " + TestAPI.prettyAmount(state.getPreviousBalance()));
			System.out.println("Current balance: " + TestAPI.prettyAmount(state.getCurrentBalance()));

			// Actual execution
			state.execute();

			System.out.println("After execution round:");
			System.out.println("Steps: " + state.getSteps());
			System.out.println(String.format("Program Counter: 0x%04x", state.getProgramCounter()));
			System.out.println(String.format("Stop Address: 0x%04x", state.getOnStopAddress()));
			System.out.println("Error Address: " + (state.getOnErrorAddress() == null ? "not set" : String.format("0x%04x", state.getOnErrorAddress())));

			if (state.isSleeping())
				System.out.println("Sleeping until current block height (" + state.getCurrentBlockHeight() + ") reaches " + state.getSleepUntilHeight());
			else
				System.out.println("Sleeping: " + state.isSleeping());

			System.out.println("Stopped: " + state.isStopped());
			System.out.println("Finished: " + state.isFinished());

			if (state.hadFatalError())
				System.out.println("Finished due to fatal error!");

			System.out.println("Frozen: " + state.isFrozen());

			long newBalance = state.getCurrentBalance();
			System.out.println("New balance: " + TestAPI.prettyAmount(newBalance));
			api.setCurrentBalance(newBalance);

			// Bump block height
			api.bumpCurrentBlockHeight();

			packedState = state.toBytes();
			System.out.println("Execution round finished\n");
		} while (!onceOnly && !state.isFinished());

		unwrapState(state);
	}

	protected byte[] unwrapState(MachineState state) {
		// Ready for diagnosis
		byte[] stateBytes = state.toBytes();

		// We know how the state will be serialized so we can extract values
		// header + data(size * 8) + callStack length(4) + callStack + userStack length(4) + userStack

		stateByteBuffer = ByteBuffer.wrap(stateBytes);
		callStackSize = stateByteBuffer.getInt(CALL_STACK_OFFSET);
		userStackOffset = CALL_STACK_OFFSET + 4 + callStackSize;
		userStackSize = stateByteBuffer.getInt(userStackOffset);

		return stateBytes;
	}

	protected long getData(int address) {
		int index = DATA_OFFSET + address * MachineState.VALUE_SIZE;
		return stateByteBuffer.getLong(index);
	}

	protected void getDataBytes(int address, byte[] dest) {
		int index = DATA_OFFSET + address * MachineState.VALUE_SIZE;
		stateByteBuffer.slice().position(index).get(dest);
	}

	protected int getCallStackPosition() {
		return TestUtils.NUM_CALL_STACK_PAGES * MachineState.ADDRESS_SIZE - callStackSize;
	}

	protected int getCallStackEntry(int address) {
		int index = CALL_STACK_OFFSET + 4 + address - TestUtils.NUM_CALL_STACK_PAGES * MachineState.ADDRESS_SIZE + callStackSize;
		return stateByteBuffer.getInt(index);
	}

	protected int getUserStackPosition() {
		return TestUtils.NUM_USER_STACK_PAGES * MachineState.VALUE_SIZE - userStackSize;
	}

	protected long getUserStackEntry(int address) {
		int index = userStackOffset + 4 + address - TestUtils.NUM_USER_STACK_PAGES * MachineState.VALUE_SIZE + userStackSize;
		return stateByteBuffer.getLong(index);
	}

}
