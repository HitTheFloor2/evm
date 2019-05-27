/**
 * Copyright (c) [2018] [ The Semux Developers ]
 * Copyright (c) [2016] [ <ether.camp> ]
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.ethereum.vm.program;

import static org.apache.commons.lang3.ArrayUtils.EMPTY_BYTE_ARRAY;
import static org.apache.commons.lang3.ArrayUtils.getLength;
import static org.apache.commons.lang3.ArrayUtils.isEmpty;
import static org.apache.commons.lang3.ArrayUtils.isNotEmpty;
import static org.apache.commons.lang3.ArrayUtils.nullToEmpty;
import static org.ethereum.vm.util.BigIntegerUtil.isNotCovers;
import static org.ethereum.vm.util.BigIntegerUtil.transfer;

import java.math.BigInteger;
import java.util.Arrays;

import org.apache.commons.lang3.tuple.Pair;
import org.ethereum.vm.DataWord;
import org.ethereum.vm.MessageCall;
import org.ethereum.vm.OpCode;
import org.ethereum.vm.PrecompiledContracts;
import org.ethereum.vm.VM;
import org.ethereum.vm.client.Repository;
import org.ethereum.vm.client.Transaction;
import org.ethereum.vm.program.exception.BytecodeExecutionException;
import org.ethereum.vm.program.exception.ExceptionFactory;
import org.ethereum.vm.program.exception.StackUnderflowException;
import org.ethereum.vm.program.invoke.ProgramInvoke;
import org.ethereum.vm.program.invoke.ProgramInvokeFactory;
import org.ethereum.vm.program.invoke.ProgramInvokeFactoryImpl;
import org.ethereum.vm.chainspec.Spec;
import org.ethereum.vm.util.HashUtil;
import org.ethereum.vm.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Program {

    private static final Logger logger = LoggerFactory.getLogger(Program.class);

    /**
     * This attribute defines the number of recursive calls allowed in the EVM Note:
     * For the JVM to reach this level without a StackOverflow exception, ethereumj
     * may need to be started with a JVM argument to increase the stack size. For
     * example: -Xss10m
     */
    private static final int MAX_DEPTH = 1024;

    // Max size for stack checks
    private static final int MAX_STACKSIZE = 1024;

    private Transaction transaction; // nullable

    private ProgramInvoke invoke;
    private ProgramInvokeFactory programInvokeFactory = new ProgramInvokeFactoryImpl();

    private Spec spec;
    private ProgramPreprocess preprocessed;

    private Stack stack;
    private Memory memory;
    private Repository repo;
    private Repository originalRepo;
    private byte[] returnDataBuffer;

    private ProgramResult result = new ProgramResult();

    private byte[] ops;
    private int pc;
    private boolean stopped;

    public Program(byte[] ops, ProgramInvoke programInvoke, Transaction transaction, Spec spec) {
        this.ops = nullToEmpty(ops);
        this.invoke = programInvoke;

        this.memory = new Memory();
        this.stack = new Stack();
        this.repo = programInvoke.getRepository();
        this.originalRepo = programInvoke.getOriginalRepository();

        this.transaction = transaction;
        this.spec = spec;
    }

    public Program(byte[] ops, ProgramInvoke programInvoke) {
        this(ops, programInvoke, null, Spec.DEFAULT);
    }

    public ProgramPreprocess getProgramPreprocess() {
        if (preprocessed == null) {
            preprocessed = ProgramPreprocess.compile(ops);
        }
        return preprocessed;
    }

    public int getCallDepth() {
        return invoke.getCallDepth();
    }

    private InternalTransaction addInternalTx(OpCode type, byte[] from, byte[] to, long nonce, BigInteger value,
            byte[] data, long gas) {

        int depth = getCallDepth();
        int index = result.getInternalTransactions().size();

        InternalTransaction tx = new InternalTransaction(transaction, depth, index, type,
                from, to, nonce, value, data, gas, getGasPrice().value());
        result.addInternalTransaction(tx);

        return tx;
    }

    public byte getCurrentOp() {
        return isEmpty(ops) ? 0 : ops[pc];
    }

    public void stackPush(byte[] data) {
        stackPush(DataWord.of(data));
    }

    public void stackPushZero() {
        stackPush(DataWord.ZERO);
    }

    public void stackPushOne() {
        stackPush(DataWord.ONE);
    }

    public void stackPush(DataWord stackWord) {
        verifyStackOverflow(0, 1); // Sanity Check
        stack.push(stackWord);
    }

    public Stack getStack() {
        return this.stack;
    }

    public int getPC() {
        return pc;
    }

    public void setPC(int pc) {
        this.pc = pc;

        if (this.pc >= ops.length) {
            stop();
        }
    }

    public boolean isStopped() {
        return stopped;
    }

    public void setHReturn(byte[] buff) {
        getResult().setReturnData(buff);
    }

    public void stop() {
        stopped = true;
    }

    public void step() {
        setPC(pc + 1);
    }

    public byte[] sweep(int n) {

        if (pc + n > ops.length)
            stop();

        byte[] data = Arrays.copyOfRange(ops, pc, pc + n);
        pc += n;
        if (pc >= ops.length)
            stop();

        return data;
    }

    public DataWord stackPop() {
        return stack.pop();
    }

    /**
     * Verifies that the stack is at least <code>stackSize</code>
     *
     * @param stackSize
     *            int
     * @throws StackUnderflowException
     *             If the stack is smaller than <code>stackSize</code>
     */
    public void verifyStackUnderflow(int stackSize) throws StackUnderflowException {
        if (stack.size() < stackSize) {
            throw ExceptionFactory.tooSmallStack(stackSize, stack.size());
        }
    }

    public void verifyStackOverflow(int argsReqs, int returnReqs) {
        if ((stack.size() - argsReqs + returnReqs) > MAX_STACKSIZE) {
            throw ExceptionFactory.tooLargeStack((stack.size() - argsReqs + returnReqs), MAX_STACKSIZE);
        }
    }

    public int getMemSize() {
        return memory.size();
    }

    public void memorySave(DataWord addrB, DataWord value) {
        memory.write(addrB.intValue(), value.getData(), value.getData().length, false);
    }

    public void memorySaveLimited(int addr, byte[] data, int dataSize) {
        memory.write(addr, data, dataSize, true);
    }

    public void memorySave(int addr, byte[] value) {
        memory.write(addr, value, value.length, false);
    }

    public void memoryExpand(DataWord outDataOffs, DataWord outDataSize) {
        if (!outDataSize.isZero()) {
            memory.extend(outDataOffs.intValue(), outDataSize.intValue());
        }
    }

    /**
     * Allocates a piece of memory and stores value at given offset address
     *
     * @param addr
     *            is the offset address
     * @param allocSize
     *            size of memory needed to write
     * @param value
     *            the data to write to memory
     */
    public void memorySave(int addr, int allocSize, byte[] value) {
        memory.extendAndWrite(addr, allocSize, value);
    }

    public DataWord memoryLoad(DataWord addr) {
        return memory.readWord(addr.intValue());
    }

    public byte[] memoryChunk(int offset, int size) {
        return memory.read(offset, size);
    }

    /**
     * Allocates extra memory in the program for a specified size, calculated from a
     * given offset
     *
     * @param offset
     *            the memory address offset
     * @param size
     *            the number of bytes to allocate
     */
    public void allocateMemory(int offset, int size) {
        memory.extend(offset, size);
    }

    public void suicide(DataWord beneficiary) {
        byte[] owner = getOwnerAddress().getLast20Bytes();
        byte[] obtainer = beneficiary.getLast20Bytes();
        BigInteger balance = getRepository().getBalance(owner);

        addInternalTx(OpCode.SUICIDE, owner, obtainer, getRepository().getNonce(owner), balance,
                EMPTY_BYTE_ARRAY, 0);

        if (Arrays.equals(owner, obtainer)) {
            // if owner == obtainer just zeroing account according to Yellow Paper
            getRepository().addBalance(owner, balance.negate());
        } else {
            transfer(getRepository(), owner, obtainer, balance);
        }

        getResult().addDeleteAccount(this.getOwnerAddress().getLast20Bytes());
    }

    public Repository getRepository() {
        return this.repo;
    }

    public Repository getOriginalRepository() {
        return this.originalRepo;
    }

    /**
     * Creates a new smart contract account.
     *
     * @param value
     * @param memStart
     * @param memSize
     */
    public void createContract(DataWord value, DataWord memStart, DataWord memSize) {
        returnDataBuffer = null; // reset return buffer right before the call

        byte[] senderAddress = this.getOwnerAddress().getLast20Bytes();
        BigInteger endowment = value.value();
        if (!verifyCall(senderAddress, endowment))
            return;

        long nonce = getRepository().getNonce(senderAddress);
        byte[] contractAddress = HashUtil.calcNewAddress(senderAddress, nonce);
        byte[] programCode = memoryChunk(memStart.intValue(), memSize.intValue());
        createContractImpl(value, programCode, contractAddress);
    }

    /**
     * Create contract for {@link OpCode#CREATE2}
     * 
     * @param value
     *            Endowment
     * @param memStart
     *            Code memory offset
     * @param memSize
     *            Code memory size
     * @param salt
     *            Salt, used in contract address calculation
     */
    public void createContract2(DataWord value, DataWord memStart, DataWord memSize, DataWord salt) {
        returnDataBuffer = null; // reset return buffer right before the call

        byte[] senderAddress = this.getOwnerAddress().getLast20Bytes();
        BigInteger endowment = value.value();
        if (!verifyCall(senderAddress, endowment))
            return;

        byte[] programCode = memoryChunk(memStart.intValue(), memSize.intValue());
        byte[] contractAddress = HashUtil.calcSaltAddress(senderAddress, programCode, salt.getData());
        createContractImpl(value, programCode, contractAddress);
    }

    /**
     * Verifies CREATE attempt
     */
    private boolean verifyCall(byte[] senderAddress, BigInteger endowment) {
        if (getCallDepth() == MAX_DEPTH) {
            stackPushZero();
            return false;
        }

        if (isNotCovers(getRepository().getBalance(senderAddress), endowment)) {
            stackPushZero();
            return false;
        }

        return true;
    }

    /**
     * All stages required to create contract on provided address after initial
     * check
     * 
     * @param value
     *            Endowment
     * @param programCode
     *            Contract code
     * @param newAddress
     *            Contract address
     */
    private void createContractImpl(DataWord value, byte[] programCode, byte[] newAddress) {
        // [1] LOG, SPEND GAS
        byte[] senderAddress = this.getOwnerAddress().getLast20Bytes();

        // actual gas subtract
        long gas = spec.getCreateGas(getAvailableGas());
        spendGas(gas, "internal call");

        boolean contractAlreadyExists = getRepository().exists(newAddress);

        // [3] UPDATE THE NONCE
        // (THIS STAGE IS NOT REVERTED BY ANY EXCEPTION)
        getRepository().increaseNonce(senderAddress);

        Repository track = getRepository().startTracking();

        // In case of hashing collisions, check for any balance before createAccount()
        BigInteger oldBalance = track.getBalance(newAddress);
        track.increaseNonce(newAddress);
        track.addBalance(newAddress, oldBalance);

        // [4] TRANSFER THE BALANCE
        BigInteger endowment = value.value();
        track.addBalance(senderAddress, endowment.negate());
        track.addBalance(newAddress, endowment);

        // [5] COOK THE INVOKE AND EXECUTE
        InternalTransaction internalTx = addInternalTx(OpCode.CREATE, senderAddress, EMPTY_BYTE_ARRAY,
                getRepository().getNonce(senderAddress), endowment, programCode, gas);
        if (logger.isDebugEnabled()) {
            logger.debug("CREATE: {}", internalTx);
        }

        ProgramInvoke programInvoke = programInvokeFactory.createProgramInvoke(this,
                getOwnerAddress(),
                DataWord.of(newAddress),
                gas,
                value,
                EMPTY_BYTE_ARRAY,
                track,
                this.invoke.getBlockStore(),
                false);

        ProgramResult result = ProgramResult.createEmpty();

        if (contractAlreadyExists) {
            result.setException(
                    new BytecodeExecutionException("Account already exists: 0x" + HexUtil.toHexString(newAddress)));
        } else if (isNotEmpty(programCode)) {
            VM vm = new VM(spec);
            Program program = new Program(programCode, programInvoke, internalTx, spec);
            vm.play(program);
            result = program.getResult();
        }

        // 4. CREATE THE CONTRACT OUT OF RETURN
        if (!result.isRevert() && result.getException() == null) {
            byte[] code = result.getReturnData();
            long storageCost = getLength(code) * spec.getFeeSchedule().getCREATE_DATA();

            long afterSpend = programInvoke.getGas() - result.getGasUsed() - storageCost;
            if (afterSpend < 0) {
                if (!spec.createEmptyContractOnOOG()) {
                    result.setException(ExceptionFactory.notEnoughSpendingGas("No gas to return just created contract",
                            storageCost, this));
                } else {
                    track.saveCode(newAddress, EMPTY_BYTE_ARRAY);
                }
            } else if (getLength(code) > spec.maxContractSize()) {
                result.setException(ExceptionFactory.notEnoughSpendingGas(
                        "Contract size too large: " + getLength(result.getReturnData()),
                        storageCost, this));
            } else {
                result.spendGas(storageCost);
                track.saveCode(newAddress, code);
            }
        }

        getResult().merge(result);

        if (result.getException() != null || result.isRevert()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Contract run halted by Exception: contract: [{}], exception: [{}]",
                        HexUtil.toHexString(newAddress),
                        result.getException());
            }

            internalTx.reject();
            result.rejectInternalTransactions();

            track.rollback();
            stackPushZero();

            if (result.getException() != null) {
                return;
            } else {
                returnDataBuffer = result.getReturnData();
            }
        } else {
            track.commit();

            // IN SUCCESS PUSH THE ADDRESS INTO THE STACK
            stackPush(DataWord.of(newAddress));
        }

        // 5. REFUND THE REMAIN GAS
        long refundGas = gas - result.getGasUsed();
        if (refundGas > 0) {
            refundGas(refundGas, "remaining gas from create");
        }
    }

    /**
     * Makes an internal call to another address.
     *
     * Note: normal calls invoke a specified contract which updates itself, while
     * Stateless calls invoke code from another contract, within the context of the
     * caller.
     *
     * @param msg
     *            the message call object
     */
    public void callToAddress(MessageCall msg) {
        returnDataBuffer = null; // reset return buffer right before the call

        if (getCallDepth() == MAX_DEPTH) {
            stackPushZero();
            refundGas(msg.getGas(), "call deep limit reach");
            return;
        }

        byte[] data = memoryChunk(msg.getInDataOffs().intValue(), msg.getInDataSize().intValue());

        byte[] codeAddress = msg.getCodeAddress().getLast20Bytes();
        byte[] senderAddress = getOwnerAddress().getLast20Bytes();
        byte[] contextAddress = msg.getType().callIsStateless() ? senderAddress : codeAddress;

        Repository track = getRepository().startTracking();

        // PERFORM VALUE CHECK
        BigInteger endowment = msg.getEndowment().value();
        BigInteger senderBalance = track.getBalance(senderAddress);
        if (isNotCovers(senderBalance, endowment)) {
            stackPushZero();
            refundGas(msg.getGas(), "refund gas from message call");
            return;
        }

        // FETCH THE CODE
        byte[] programCode = getRepository().exists(codeAddress) ? getRepository().getCode(codeAddress)
                : EMPTY_BYTE_ARRAY;

        // TRANSFER
        track.addBalance(senderAddress, endowment.negate());
        track.addBalance(contextAddress, endowment);

        // CREATE CALL INTERNAL TRANSACTION
        InternalTransaction internalTx = addInternalTx(msg.getType(), senderAddress, contextAddress,
                getRepository().getNonce(senderAddress), endowment, data, msg.getGas());
        if (logger.isDebugEnabled()) {
            logger.debug("CALL: {}", internalTx);
        }

        ProgramResult result = null;
        if (isNotEmpty(programCode)) {
            ProgramInvoke programInvoke = programInvokeFactory.createProgramInvoke(this,
                    msg.getType().callIsDelegate() ? getCallerAddress() : getOwnerAddress(),
                    DataWord.of(contextAddress),
                    msg.getGas(),
                    msg.getType().callIsDelegate() ? getCallValue() : msg.getEndowment(),
                    data,
                    track,
                    this.invoke.getBlockStore(),
                    msg.getType().callIsStatic() || isStaticCall());

            VM vm = new VM(spec);
            Program program = new Program(programCode, programInvoke, internalTx, spec);
            vm.play(program);
            result = program.getResult();

            getResult().merge(result);

            if (result.getException() != null || result.isRevert()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("contract run halted by Exception: contract: [{}], exception: [{}]",
                            HexUtil.toHexString(contextAddress),
                            result.getException());
                }

                internalTx.reject();
                result.rejectInternalTransactions();

                track.rollback();
                stackPushZero();

                if (result.getException() != null) {
                    return;
                }
            } else {
                track.commit();
                stackPushOne();
            }

        } else {
            track.commit();
            stackPushOne();
        }

        // APPLY RESULTS: result.getReturnData() into out_memory allocated
        if (result != null) {
            byte[] buffer = result.getReturnData();
            int offset = msg.getOutDataOffs().intValue();
            int size = msg.getOutDataSize().intValue();

            memorySaveLimited(offset, buffer, size);

            returnDataBuffer = buffer;
        }

        // 5. REFUND THE REMAIN GAS
        if (result != null) {
            long refundGas = msg.getGas() - result.getGasUsed();
            if (refundGas > 0) {
                refundGas(refundGas, "remaining gas from call");
            }
        } else {
            refundGas(msg.getGas(), "remaining gas from call");
        }
    }

    public void callToPrecompiledAddress(MessageCall msg, PrecompiledContracts.PrecompiledContract contract) {
        returnDataBuffer = null; // reset return buffer right before the call

        if (getCallDepth() == MAX_DEPTH) {
            stackPushZero();
            this.refundGas(msg.getGas(), "call deep limit reach");
            return;
        }

        Repository track = getRepository().startTracking();

        byte[] senderAddress = this.getOwnerAddress().getLast20Bytes();
        byte[] codeAddress = msg.getCodeAddress().getLast20Bytes();
        byte[] contextAddress = msg.getType().callIsStateless() ? senderAddress : codeAddress;

        BigInteger endowment = msg.getEndowment().value();
        BigInteger senderBalance = track.getBalance(senderAddress);
        if (senderBalance.compareTo(endowment) < 0) {
            stackPushZero();
            this.refundGas(msg.getGas(), "refund gas from message call");
            return;
        }

        byte[] data = this.memoryChunk(msg.getInDataOffs().intValue(),
                msg.getInDataSize().intValue());

        // Charge for endowment - is not reversible by rollback
        transfer(track, senderAddress, contextAddress, msg.getEndowment().value());

        long requiredGas = contract.getGasForData(data);

        if (requiredGas > msg.getGas()) {
            this.refundGas(0, "refund gas from pre-compiled call");
            this.stackPushZero();
            track.rollback();
        } else {
            Pair<Boolean, byte[]> out = contract.execute(data);

            if (out.getLeft()) {
                this.refundGas(msg.getGas() - requiredGas, "refund gas from pre-compiled call");
                this.stackPushOne();
                returnDataBuffer = out.getRight();
                track.commit();
            } else {
                this.refundGas(0, "refund gas from pre-compiled call");
                this.stackPushZero();
                track.rollback();
            }

            this.memorySave(msg.getOutDataOffs().intValue(), msg.getOutDataSize().intValueSafe(), out.getRight());
        }
    }

    public void spendGas(long gasValue, String cause) {
        logger.debug("Spend: cause = [{}], gas = [{}]", cause, gasValue);

        if (getAvailableGas() < gasValue) {
            throw ExceptionFactory.notEnoughSpendingGas(cause, gasValue, this);
        }
        getResult().spendGas(gasValue);
    }

    public void spendAllGas() {
        spendGas(getAvailableGas(), "consume all");
    }

    public void refundGas(long gasValue, String cause) {
        logger.debug("Refund: cause = [{}], gas = [{}]", cause, gasValue);

        getResult().refundGas(gasValue);
    }

    public void futureRefundGas(long gasValue) {
        logger.debug("Future refund added: [{}]", gasValue);

        getResult().addFutureRefund(gasValue);
    }

    public void resetFutureRefund() {
        getResult().resetFutureRefund();
    }

    public void storageSave(DataWord key, DataWord value) {
        getRepository().putStorageRow(getOwnerAddress().getLast20Bytes(), key, value);
    }

    public byte[] getCode() {
        return ops;
    }

    public byte[] getCodeAt(DataWord address) {
        byte[] code = invoke.getRepository().getCode(address.getLast20Bytes());
        return nullToEmpty(code);
    }

    public DataWord getOwnerAddress() {
        return invoke.getOwnerAddress();
    }

    public DataWord getBlockHash(int index) {
        return index < this.getNumber().longValue() && index >= Math.max(256, this.getNumber().intValue()) - 256
                ? DataWord.of(this.invoke.getBlockStore().getBlockHashByNumber(index))
                : DataWord.ZERO;
    }

    public DataWord getBalance(DataWord address) {
        BigInteger balance = getRepository().getBalance(address.getLast20Bytes());
        return DataWord.of(balance.toByteArray());
    }

    public DataWord getOriginAddress() {
        return invoke.getOriginAddress();
    }

    public DataWord getCallerAddress() {
        return invoke.getCallerAddress();
    }

    public DataWord getGasPrice() {
        return invoke.getGasPrice();
    }

    public long getAvailableGas() {
        return invoke.getGas() - getResult().getGasUsed();
    }

    public DataWord getCallValue() {
        return invoke.getValue();
    }

    public DataWord getDataSize() {
        return invoke.getDataSize();
    }

    public DataWord getDataValue(DataWord index) {
        return invoke.getDataValue(index);
    }

    public byte[] getDataCopy(DataWord offset, DataWord length) {
        return invoke.getDataCopy(offset, length);
    }

    public DataWord getReturnDataBufferSize() {
        return DataWord.of(getReturnDataBufferSizeI());
    }

    private int getReturnDataBufferSizeI() {
        return returnDataBuffer == null ? 0 : returnDataBuffer.length;
    }

    public byte[] getReturnDataBufferData(DataWord off, DataWord size) {
        if ((long) off.intValueSafe() + size.intValueSafe() > getReturnDataBufferSizeI())
            return null;
        return returnDataBuffer == null ? EMPTY_BYTE_ARRAY
                : Arrays.copyOfRange(returnDataBuffer, off.intValueSafe(), off.intValueSafe() + size.intValueSafe());
    }

    /**
     * Returns the current storage data for key
     */
    public DataWord getCurrentStorageValue(DataWord key) {
        return getRepository().getStorageRow(getOwnerAddress().getLast20Bytes(), key);
    }

    /**
     * Returns the storage data at the beginning of program execution
     */
    public DataWord getOriginalStorageValue(DataWord key) {
        return getOriginalRepository().getStorageRow(getOwnerAddress().getLast20Bytes(), key);
    }

    public DataWord getCoinbase() {
        return invoke.getCoinbase();
    }

    public DataWord getTimestamp() {
        return invoke.getTimestamp();
    }

    public DataWord getNumber() {
        return invoke.getNumber();
    }

    public DataWord getDifficulty() {
        return invoke.getDifficulty();
    }

    public DataWord getGasLimit() {
        return invoke.getGaslimit();
    }

    public boolean isStaticCall() {
        return invoke.isStaticCall();
    }

    public ProgramResult getResult() {
        return result;
    }

    public void setRuntimeFailure(RuntimeException e) {
        getResult().setException(e);
    }

    public DataWord getPrevHash() {
        return invoke.getPrevHash();
    }

    public int verifyJumpDest(DataWord nextPC) {
        if (nextPC.bytesOccupied() > 4) {
            throw ExceptionFactory.badJumpDestination(-1);
        }
        int ret = nextPC.intValue();
        if (!getProgramPreprocess().hasJumpDest(ret)) {
            throw ExceptionFactory.badJumpDestination(ret);
        }
        return ret;
    }

    /**
     * used mostly for testing reasons
     */
    public byte[] getMemory() {
        return memory.read(0, memory.size());
    }

    /**
     * used mostly for testing reasons
     */
    public void initMem(byte[] data) {
        this.memory.write(0, data, data.length, false);
    }
}
