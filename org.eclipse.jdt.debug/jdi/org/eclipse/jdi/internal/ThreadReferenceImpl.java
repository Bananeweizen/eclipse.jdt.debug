package org.eclipse.jdi.internal;

/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import org.eclipse.jdi.internal.jdwp.JdwpCommandPacket;
import org.eclipse.jdi.internal.jdwp.JdwpID;
import org.eclipse.jdi.internal.jdwp.JdwpReplyPacket;
import org.eclipse.jdi.internal.jdwp.JdwpThreadID;

import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InternalException;
import com.sun.jdi.InvalidStackFrameException;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadGroupReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;


/**
 * This class implements the corresponding interfaces
 * declared by the JDI specification. See the com.sun.jdi package
 * for more information.
 *
 */
public class ThreadReferenceImpl extends ObjectReferenceImpl implements ThreadReference, org.eclipse.jdi.hcr.ThreadReference {
	/** ThreadStatus Constants. */
	public static final int JDWP_THREAD_STATUS_ZOMBIE = 0;
	public static final int JDWP_THREAD_STATUS_RUNNING = 1;
	public static final int JDWP_THREAD_STATUS_SLEEPING = 2;
	public static final int JDWP_THREAD_STATUS_MONITOR = 3;
	public static final int JDWP_THREAD_STATUS_WAIT = 4;

	/** SuspendStatus Constants. */
	public static final int SUSPEND_STATUS_SUSPENDED = 0x01;
	
	/** Mapping of command codes to strings. */
	private static HashMap fThreadStatusMap = null;

	/** Map with Strings for flag bits. */
	private static Vector fSuspendStatusVector = null;

	/** JDWP Tag. */
	protected static final byte tag = JdwpID.THREAD_TAG;

	/** Is thread currently at a breakpoint? */
	private boolean fIsAtBreakpoint = false;
	
	/** The following are the stored results of JDWP calls. */
	private String fName = null;
	private ThreadGroupReferenceImpl fThreadGroup = null;

	/**
	 * Creates new ThreadReferenceImpl.
	 */
	public ThreadReferenceImpl(VirtualMachineImpl vmImpl, JdwpThreadID threadID) {
		super("ThreadReference", vmImpl, threadID); //$NON-NLS-1$
	}

	/**
	 * Sets at breakpoint flag.
	 */
	public void setIsAtBreakpoint() {
		fIsAtBreakpoint = true;
	}
	
	/**
	 * Reset flags that can be set when event occurs.
	 */
	public void resetEventFlags() {
		fIsAtBreakpoint = false;
	}

	/**
	 * @returns Value tag.
	 */
	public byte getTag() {
		return tag;
	}
	
	/**
	 * @returns Returns an ObjectReference for the monitor, if any, for which this thread is currently waiting.
	 */
	public ObjectReference currentContendedMonitor() throws IncompatibleThreadStateException {
		// Note that this information should not be cached.
		initJdwpRequest();
		try {
			JdwpReplyPacket replyPacket = requestVM(JdwpCommandPacket.TR_CURRENT_CONTENDED_MONITOR, this);
			switch (replyPacket.errorCode()) {
				case JdwpReplyPacket.INVALID_THREAD:
					throw new ObjectCollectedException();
				case JdwpReplyPacket.THREAD_NOT_SUSPENDED:
					throw new IncompatibleThreadStateException(JDIMessages.getString("ThreadReferenceImpl.Thread_was_not_suspended_1")); //$NON-NLS-1$
			}
			defaultReplyErrorHandler(replyPacket.errorCode());
			
			DataInputStream replyData = replyPacket.dataInStream();
			ObjectReference result = ObjectReferenceImpl.readObjectRefWithTag(this, replyData);
			return result;
		} catch (IOException e) {
			defaultIOExceptionHandler(e);
			return null;
		} finally {
			handledJdwpRequest();
		}
	}
	
	/**
	 * @returns Returns the StackFrame at the given index in the thread's current call stack. 
	 */
	public StackFrame frame(int index) throws IncompatibleThreadStateException {
		return (StackFrameImpl)frames(index, 1).get(0);
	}
	
	public int frameCount() throws IncompatibleThreadStateException {
		// Note that this information should not be cached.
		initJdwpRequest();
		try {
			JdwpReplyPacket replyPacket = requestVM(JdwpCommandPacket.TR_FRAME_COUNT, this);
			switch (replyPacket.errorCode()) {
				case JdwpReplyPacket.INVALID_THREAD:
					throw new ObjectCollectedException();
				case JdwpReplyPacket.THREAD_NOT_SUSPENDED:
					throw new IncompatibleThreadStateException(JDIMessages.getString("ThreadReferenceImpl.Thread_was_not_suspended_1")); //$NON-NLS-1$
			}
			defaultReplyErrorHandler(replyPacket.errorCode());
			
			DataInputStream replyData = replyPacket.dataInStream();
			int result = readInt("frame count", replyData); //$NON-NLS-1$
			return result;
		} catch (IOException e) {
			defaultIOExceptionHandler(e);
			return 0;
		} finally {
			handledJdwpRequest();
		}
	}
		
	/**
	 * @returns Returns a List containing each StackFrame in the thread's current call stack.
	 */
	public List frames() throws IncompatibleThreadStateException {
		return frames(0, -1);
	}
	
	/**
	 * @returns Returns a List containing each StackFrame in the thread's current call stack.
	 */
	public List frames(int start, int length) throws IndexOutOfBoundsException, IncompatibleThreadStateException {
		// Note that this information should not be cached.
		initJdwpRequest();
		try {
			ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
			DataOutputStream outData = new DataOutputStream(outBytes);
			write(this, outData);
			writeInt(start, "start", outData); //$NON-NLS-1$
			writeInt(length, "length", outData); //$NON-NLS-1$
	
			JdwpReplyPacket replyPacket = requestVM(JdwpCommandPacket.TR_FRAMES, outBytes);
			switch (replyPacket.errorCode()) {
				case JdwpReplyPacket.INVALID_THREAD:
					throw new ObjectCollectedException();
				case JdwpReplyPacket.THREAD_NOT_SUSPENDED:
					throw new IncompatibleThreadStateException(JDIMessages.getString("ThreadReferenceImpl.Thread_was_not_suspended_1")); //$NON-NLS-1$
				case JdwpReplyPacket.INVALID_INDEX:
					throw new IndexOutOfBoundsException(JDIMessages.getString("ThreadReferenceImpl.Invalid_index_of_stack_frames_given_4")); //$NON-NLS-1$
			}
			defaultReplyErrorHandler(replyPacket.errorCode());
			
			DataInputStream replyData = replyPacket.dataInStream();
			int nrOfElements = readInt("elements", replyData); //$NON-NLS-1$
			Vector frames = new Vector();
			for (int i = 0; i < nrOfElements; i++) {
				StackFrameImpl frame = StackFrameImpl.readWithLocation(this, this, replyData);
				if (frame == null)
					continue;
				frames.add(frame);
			}
			return frames;
		} catch (IOException e) {
			defaultIOExceptionHandler(e);
			return null;
		} finally {
			handledJdwpRequest();
		}
	}

	/**
	 * Interrupts this thread.
	 */
	public void interrupt() {
		// Note that this information should not be cached.
		initJdwpRequest();
		try {
			requestVM(JdwpCommandPacket.TR_INTERRUPT, this);
		} finally {
			handledJdwpRequest();
		}
	}

	/**
	 * @return Returns whether the thread is suspended at a breakpoint.
	 */
	public boolean isAtBreakpoint() {
		return isSuspended() && fIsAtBreakpoint;
	}

	/**
	 * @return Returns whether the thread has been suspended by the the debugger.
	 */
	public boolean isSuspended() {
		// Note that this information should not be cached.
		initJdwpRequest();
		try {
			JdwpReplyPacket replyPacket = requestVM(JdwpCommandPacket.TR_STATUS, this);
			switch (replyPacket.errorCode()) {
				case JdwpReplyPacket.INVALID_THREAD:
					throw new ObjectCollectedException();
			}
			defaultReplyErrorHandler(replyPacket.errorCode());
			DataInputStream replyData = replyPacket.dataInStream();
			//remove the thread status reply
			readInt("thread status", threadStatusMap(), replyData); //$NON-NLS-1$
			int suspendStatus = readInt("suspend status", suspendStatusVector(), replyData); //$NON-NLS-1$
			boolean result = suspendStatus == SUSPEND_STATUS_SUSPENDED;
			return result;
		} catch (IOException e) {
			defaultIOExceptionHandler(e);
			return false;
		} finally {
			handledJdwpRequest();
		}
	}

	/**
	 * @return Returns the name of this thread.
	 */
	public String name() {
		initJdwpRequest();
		try {
			JdwpReplyPacket replyPacket = requestVM(JdwpCommandPacket.TR_NAME, this);
			switch (replyPacket.errorCode()) {
				case JdwpReplyPacket.INVALID_THREAD:
					throw new ObjectCollectedException();
			}
			defaultReplyErrorHandler(replyPacket.errorCode());
			DataInputStream replyData = replyPacket.dataInStream();
			return readString("name", replyData); //$NON-NLS-1$
		} catch (IOException e) {
			defaultIOExceptionHandler(e);
			return null;
		} finally {
			handledJdwpRequest();
		}
	}
	
	/**
	 * @return Returns a List containing an ObjectReference for each monitor owned by the thread. 
	 */
	public List ownedMonitors() throws IncompatibleThreadStateException {
		// Note that this information should not be cached.
		initJdwpRequest();
		try {
			JdwpReplyPacket replyPacket = requestVM(JdwpCommandPacket.TR_OWNED_MONITORS, this);
			switch (replyPacket.errorCode()) {
				case JdwpReplyPacket.INVALID_THREAD:
					throw new ObjectCollectedException();
				case JdwpReplyPacket.THREAD_NOT_SUSPENDED:
					throw new IncompatibleThreadStateException(JDIMessages.getString("ThreadReferenceImpl.Thread_was_not_suspended_5")); //$NON-NLS-1$
			}
			defaultReplyErrorHandler(replyPacket.errorCode());
			DataInputStream replyData = replyPacket.dataInStream();
			Vector result = new Vector();
			int nrOfMonitors = readInt("nr of monitors", replyData); //$NON-NLS-1$
			for (int i = 0; i < nrOfMonitors; i++)
				result.add(ObjectReferenceImpl.readObjectRefWithTag(this, replyData));
			return result;
		} catch (IOException e) {
			defaultIOExceptionHandler(e);
			return null;
		} finally {
			handledJdwpRequest();
		}
	}

	/**
	 * Resumes this thread. 
	 */
	public void resume() {
		initJdwpRequest();
		try {
		   	JdwpReplyPacket replyPacket = requestVM(JdwpCommandPacket.TR_RESUME, this);
			switch (replyPacket.errorCode()) {
				case JdwpReplyPacket.INVALID_THREAD:
					throw new ObjectCollectedException();
			}
			defaultReplyErrorHandler(replyPacket.errorCode());
			resetEventFlags();
		} finally {
			handledJdwpRequest();
		}
	}
	
	/**
	 * @return Returns the thread's status.
	 */
	public int status() {
		// Note that this information should not be cached.
		initJdwpRequest();
		try {
			JdwpReplyPacket replyPacket = requestVM(JdwpCommandPacket.TR_STATUS, this);
			switch (replyPacket.errorCode()) {
				case JdwpReplyPacket.ABSENT_INFORMATION:
					return THREAD_STATUS_UNKNOWN;
				case JdwpReplyPacket.INVALID_THREAD:
					return THREAD_STATUS_NOT_STARTED;
			}
			defaultReplyErrorHandler(replyPacket.errorCode());
			DataInputStream replyData = replyPacket.dataInStream();
			int threadStatus = readInt("thread status", threadStatusMap(), replyData); //$NON-NLS-1$
			readInt("suspend status", suspendStatusVector(), replyData); //$NON-NLS-1$
			switch (threadStatus) {
				case JDWP_THREAD_STATUS_ZOMBIE:
					return THREAD_STATUS_ZOMBIE;
				case JDWP_THREAD_STATUS_RUNNING:
					return THREAD_STATUS_RUNNING;
				case JDWP_THREAD_STATUS_SLEEPING:
					return THREAD_STATUS_SLEEPING;
				case JDWP_THREAD_STATUS_MONITOR:
					return THREAD_STATUS_MONITOR;
				case JDWP_THREAD_STATUS_WAIT:
					return THREAD_STATUS_WAIT;
			}
			throw new InternalException(JDIMessages.getString("ThreadReferenceImpl.Unknown_thread_status_received___6") + threadStatus); //$NON-NLS-1$
		} catch (IOException e) {
			defaultIOExceptionHandler(e);
			return 0;
		} finally {
			handledJdwpRequest();
		}
	}
	
	/**
	 * Stops this thread with an asynchronous exception. 
	 */
	public void stop(ObjectReference throwable) throws InvalidTypeException {
		checkVM(throwable);
		ObjectReferenceImpl throwableImpl = (ObjectReferenceImpl) throwable;
		
		initJdwpRequest();
		try {
			ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
			DataOutputStream outData = new DataOutputStream(outBytes);
			write(this, outData);
			throwableImpl.write(this, outData);
	
			JdwpReplyPacket replyPacket = requestVM(JdwpCommandPacket.TR_STOP, outBytes);
			switch (replyPacket.errorCode()) {
				case JdwpReplyPacket.INVALID_THREAD:
					throw new ObjectCollectedException();
				case JdwpReplyPacket.INVALID_CLASS:
					throw new InvalidTypeException (JDIMessages.getString("ThreadReferenceImpl.Stop_argument_not_an_instance_of_java.lang.Throwable_in_the_target_VM_7")); //$NON-NLS-1$
			}
			defaultReplyErrorHandler(replyPacket.errorCode());
		} catch (IOException e) {
			defaultIOExceptionHandler(e);
		} finally {
			handledJdwpRequest();
		}
	}
		
	/**
	 * Suspends this thread. 
	 */
	public void suspend() {
		initJdwpRequest();
		try {
		   	JdwpReplyPacket replyPacket = requestVM(JdwpCommandPacket.TR_SUSPEND, this);
			switch (replyPacket.errorCode()) {
				case JdwpReplyPacket.INVALID_THREAD:
					throw new ObjectCollectedException();
			}
			defaultReplyErrorHandler(replyPacket.errorCode());
		} finally {
			handledJdwpRequest();
		}
	}

	/**
	 * @return Returns the number of pending suspends for this thread. 
	 */
	public int suspendCount() {
		// Note that this information should not be cached.
		initJdwpRequest();
		try {
			JdwpReplyPacket replyPacket = requestVM(JdwpCommandPacket.TR_SUSPEND_COUNT, this);
			defaultReplyErrorHandler(replyPacket.errorCode());
			DataInputStream replyData = replyPacket.dataInStream();
			int result = readInt("suspend count", replyData); //$NON-NLS-1$
			return result;
		} catch (IOException e) {
			defaultIOExceptionHandler(e);
			return 0;
		} finally {
			handledJdwpRequest();
		}
	}
	
	/**
	 * @return Returns this thread's thread group.
	 */
	public ThreadGroupReference threadGroup() {
		initJdwpRequest();
		try {
			JdwpReplyPacket replyPacket = requestVM(JdwpCommandPacket.TR_THREAD_GROUP, this);
			switch (replyPacket.errorCode()) {
				case JdwpReplyPacket.INVALID_THREAD:
					throw new ObjectCollectedException();
			}
			defaultReplyErrorHandler(replyPacket.errorCode());
			DataInputStream replyData = replyPacket.dataInStream();
			return ThreadGroupReferenceImpl.read(this, replyData);
		} catch (IOException e) {
			defaultIOExceptionHandler(e);
			return null;
		} finally {
			handledJdwpRequest();
		}
	}
	
	/**
	 * Simulate the execution of a return instruction instead of executing the next bytecode in a method.
	 * @return Returns whether any finally or synchronized blocks are enclosing the current instruction.
	 */
	public boolean doReturn(Value returnValue, boolean triggerFinallyAndSynchronized) throws org.eclipse.jdi.hcr.OperationRefusedException {
		virtualMachineImpl().checkHCRSupported();
		ValueImpl valueImpl;
		if (returnValue != null) {	// null is used if no value is returned.
			checkVM(returnValue);
			valueImpl = (ValueImpl)returnValue;
		} else {
			try {
				TypeImpl returnType = (TypeImpl)frame(0).location().method().returnType();
				valueImpl = (ValueImpl)returnType.createNullValue();
			} catch (IncompatibleThreadStateException e) {
				throw new org.eclipse.jdi.hcr.OperationRefusedException(e.toString());
			} catch (ClassNotLoadedException e) {
				throw new org.eclipse.jdi.hcr.OperationRefusedException(e.toString());
			}
		}

		// Note that this information should not be cached.
		initJdwpRequest();
		try {
			ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
			DataOutputStream outData = new DataOutputStream(outBytes);
			write(this, outData);
			valueImpl.writeWithTag(this, outData);
			writeBoolean(triggerFinallyAndSynchronized, "trigger finaly+sync", outData); //$NON-NLS-1$
	
			JdwpReplyPacket replyPacket = requestVM(JdwpCommandPacket.HCR_DO_RETURN, outBytes);
			switch (replyPacket.errorCode()) {
				case JdwpReplyPacket.INVALID_THREAD:
					throw new ObjectCollectedException();
			}
			defaultReplyErrorHandler(replyPacket.errorCode());
			
			DataInputStream replyData = replyPacket.dataInStream();
			boolean result = readBoolean("is enclosed", replyData); //$NON-NLS-1$
			return result;
		} catch (IOException e) {
			defaultIOExceptionHandler(e);
			return false;
		} finally {
			handledJdwpRequest();
		}
	}
	
	/**
	 * @return Returns description of Mirror object.
	 */
	public String toString() {
		try {
			return MessageFormat.format(JDIMessages.getString("ThreadReferenceImpl.8"), new String[]{type().toString(), name(), getObjectID().toString()}); //$NON-NLS-1$
		} catch (ObjectCollectedException e) {
			return JDIMessages.getString("ThreadReferenceImpl.(Garbage_Collected)_ThreadReference__9") + idString(); //$NON-NLS-1$
		} catch (Exception e) {
			return fDescription;
		}
	}

	/**
	 * @return Reads JDWP representation and returns new instance.
	 */
	public static ThreadReferenceImpl read(MirrorImpl target, DataInputStream in)  throws IOException {
		VirtualMachineImpl vmImpl = target.virtualMachineImpl();
		JdwpThreadID ID = new JdwpThreadID(vmImpl);
		ID.read(in);
		if (target.fVerboseWriter != null)
			target.fVerboseWriter.println("threadReference", ID.value()); //$NON-NLS-1$

		if (ID.isNull())
			return null;
			
		ThreadReferenceImpl mirror = (ThreadReferenceImpl)vmImpl.getCachedMirror(ID);
		if (mirror == null) {
			mirror = new ThreadReferenceImpl(vmImpl, ID);
			vmImpl.addCachedMirror(mirror);
		}
		return mirror;
	 }

	/**
	 * Retrieves constant mappings.
	 */
	public static void getConstantMaps() {
		if (fThreadStatusMap != null)
			return;
		
		java.lang.reflect.Field[] fields = ThreadReferenceImpl.class.getDeclaredFields();
		fThreadStatusMap = new HashMap();
		fSuspendStatusVector = new Vector();
		fSuspendStatusVector.setSize(32); // Int

		for (int i = 0; i < fields.length; i++) {
			java.lang.reflect.Field field = fields[i];
			if ((field.getModifiers() & java.lang.reflect.Modifier.PUBLIC) == 0 || (field.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0 || (field.getModifiers() & java.lang.reflect.Modifier.FINAL) == 0)
				continue;
				
			try {
				String name = field.getName();
				int value = field.getInt(null);
				Integer intValue = new Integer(value);

				if (name.startsWith("JDWP_THREAD_STATUS_")) { //$NON-NLS-1$
					name = name.substring(19);
					fThreadStatusMap.put(intValue, name);
				} else if (name.startsWith("SUSPEND_STATUS_")) { //$NON-NLS-1$
					name = name.substring(15);
					for (int j = 0; j < fSuspendStatusVector.size(); j++) {
						if ((1 << j & value) != 0) {
							fSuspendStatusVector.set(j, name);
							break;
						}
					}
				}
			} catch (IllegalAccessException e) {
				// Will not occur for own class.
			} catch (IllegalArgumentException e) {
				// Should not occur.
				// We should take care that all public static final constants
				// in this class are numbers that are convertible to int.
			}
		}
	}
	
	/**
	 * @return Returns a map with string representations of tags.
	 */
	 public static Map threadStatusMap() {
	 	getConstantMaps();
	 	return fThreadStatusMap;
	 }

	/**
	 * @return Returns a map with string representations of tags.
	 */
	 public static Vector suspendStatusVector() {
	 	getConstantMaps();
	 	return fSuspendStatusVector;
	 }

	/**
	 * @see ThreadReference#popFrames(StackFrame)
	 */
	public void popFrames(StackFrame frameToPop) throws IncompatibleThreadStateException {
		if (!isSuspended()) {
			throw new IncompatibleThreadStateException();
		}
		if (!virtualMachineImpl().canPopFrames()) {
			throw new UnsupportedOperationException();
		}
		
		StackFrameImpl frame = (StackFrameImpl) frameToPop;
		
		initJdwpRequest();
		try {
			ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
			DataOutputStream outData = new DataOutputStream(outBytes);
			frame.writeWithThread(frame, outData);
			
			JdwpReplyPacket replyPacket = requestVM(JdwpCommandPacket.SF_POP_FRAME, outBytes);
			switch (replyPacket.errorCode()) {
				case JdwpReplyPacket.INVALID_THREAD:
					throw new InvalidStackFrameException();
				case JdwpReplyPacket.INVALID_FRAMEID:
					throw new InvalidStackFrameException(JDIMessages.getString("ThreadReferenceImpl.Unable_to_pop_the_requested_stack_frame_from_the_call_stack_(Reasons_include__The_frame_id_was_invalid;_The_thread_was_resumed)_10")); //$NON-NLS-1$
				case JdwpReplyPacket.THREAD_NOT_SUSPENDED:
					throw new IncompatibleThreadStateException(JDIMessages.getString("ThreadReferenceImpl.Unable_to_pop_the_requested_stack_frame._The_requested_stack_frame_is_not_suspended_11")); //$NON-NLS-1$
				case JdwpReplyPacket.NO_MORE_FRAMES:
					throw new InvalidStackFrameException(JDIMessages.getString("ThreadReferenceImpl.Unable_to_pop_the_requested_stack_frame_from_the_call_stack_(Reasons_include__The_requested_frame_was_the_last_frame_on_the_call_stack;_The_requested_frame_was_the_last_frame_above_a_native_frame)_12")); //$NON-NLS-1$
				default:
					defaultReplyErrorHandler(replyPacket.errorCode());
			}
		} catch (IOException ioe) {
			defaultIOExceptionHandler(ioe);
		} finally {
			handledJdwpRequest();
		}
	}

}