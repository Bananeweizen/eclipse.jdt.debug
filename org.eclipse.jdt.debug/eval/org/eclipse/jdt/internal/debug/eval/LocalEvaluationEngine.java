package org.eclipse.jdt.internal.debug.eval;

/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.Message;
import org.eclipse.jdt.core.eval.ICodeSnippetRequestor;
import org.eclipse.jdt.core.eval.IEvaluationContext;
import org.eclipse.jdt.debug.core.IEvaluationRunnable;
import org.eclipse.jdt.debug.core.IJavaClassObject;
import org.eclipse.jdt.debug.core.IJavaClassType;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.core.IJavaType;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.eclipse.jdt.debug.core.IJavaVariable;
import org.eclipse.jdt.debug.core.JDIDebugModel;
import org.eclipse.jdt.debug.eval.IClassFileEvaluationEngine;
import org.eclipse.jdt.debug.eval.ICompiledExpression;
import org.eclipse.jdt.debug.eval.IEvaluationListener;
import org.eclipse.jdt.internal.debug.core.JDIDebugPlugin;
import org.eclipse.jdt.internal.debug.core.model.JDIDebugTarget;
import org.eclipse.jdt.internal.debug.core.model.JDIValue;

import com.sun.jdi.InvocationException;
import com.sun.jdi.ObjectReference; 

/**
 * An evaluation engine that deploys class files locally
 */

public class LocalEvaluationEngine implements IClassFileEvaluationEngine, ICodeSnippetRequestor , IEvaluationRunnable {	 
	
	/**
	 * The Java project context in which to compile snippets.
	 */
	private IJavaProject fJavaProject;
	
	/**
	 * The debug target on which to execute snippets
	 */
	private IJavaDebugTarget fDebugTarget;
	
	/**
	 * The location in which to deploy snippet class files
	 */
	private File fOutputDirectory;
	
	/**
	 * The listener to notify when the current evaluation
	 * is complete.
	 */
	private IEvaluationListener fListener;
	
	/**
	 * The stack frame context for the current evaluation
	 * or <code>null</code> if there is no stack frame
	 * context.
	 */
	private IJavaStackFrame fStackFrame;
	
	/**
	 * The result of this evaluation
	 */
	private EvaluationResult fResult;
	
	/**
	 * Collection of depolyed snippet class files
	 */
	private List fSnippetFiles;
	
	/**
	 * Collection of directories created by this evaluation
	 * engine.
	 */
	private List fDirectories;
	
	/**
	 * Evaluation context for the Java project associated
	 * with this evaluation engine.
	 */
	private IEvaluationContext fEvaluationContext;
		
	/**
	 * Array of modifier constants for visible local variables
	 * in the current evaluation.
	 * 
	 * XXX: constants should be 'default' or 'final'. Where
	 * 	are these constants defined.
	 */
	private int[] fLocalVariableModifiers;
	
	/**
	 * Array of names of visible local variables
	 * in the current evaluation.
	 */
	private String[] fLocalVariableNames;
	
	/**
	 * Array of type names of visible local variables
	 * in the current evaluation.
	 */
	private String[] fLocalVariableTypeNames;
	
	/**
	 * The 'this' object for the current evaluation
	 * or <code>null</code> if there is no 'this'
	 * context (static method, or not context)
	 */
	private IJavaObject fThis;
	
	/**
	 * Whether this engine has been disposed.
	 */
	private boolean fDisposed = false;
	
	/**
	 * The number of evaluations currently being
	 * performed.
	 */
	private int fEvaluationCount = 0;
	
	/**
	 * The name of the code snippet class to instantiate
	 */
	private String fCodeSnippetClassName = null;
		
	/**
	 * Constant for empty array of <code>java.lang.String</code>
	 */
	private static final String[] EMPTY_STRING_ARRAY = new String[0];
	
	/**
	 * Constant for empty array of <code>int</code>
	 */
	private static final int[] EMPTY_INT_ARRAY = new int[0];
		
	/**
	 * Cosntructs a new evaluation engine for the given VM in the context
	 * of the specified project. Class files required for the evaluation will
	 * be deployed to the specified directory (which must be on the class
	 * path of the VM in order for evaluation to work).
	 * 
	 * @param project context in which to compile snippets
	 * @param vm debug target in which to evaluate snippets
	 * @param directory location where snippet class files will
	 *  be deployed for execution. The directory must exist
	 */
	public LocalEvaluationEngine(IJavaProject project, IJavaDebugTarget vm, File directory) {
		setJavaProject(project);
		setDebugTarget(vm);
		setOutputDirectory(directory);
	}

	/**
	 * @see ICodeSnippetRequestor#acceptClassFiles(byte[][], String[][], String)
	 */
	public boolean acceptClassFiles(
		byte[][] classFileBytes,
		String[][] classFileCompoundNames,
		String codeSnippetClassName) {
			try {
				deploy(classFileBytes, classFileCompoundNames);
			} catch (DebugException e) {
				getResult().setException(e);
				return false;
			}
			if (codeSnippetClassName != null) {
				setCodeSnippetClassName(codeSnippetClassName);
				try {
					getThread().runEvaluation(this, null, DebugEvent.EVALUATION);
				} catch (DebugException e) {
					// exception handling is in evaluation runnable
				}
			}
			return true;
	}
	
	public void run(IJavaThread thread, IProgressMonitor monitor) {
		IJavaObject codeSnippetInstance = null;
		try {
			codeSnippetInstance = newInstance(getCodeSnippetClassName());
			initializeLocals(codeSnippetInstance);	
			codeSnippetInstance.sendMessage(RUN_METHOD, "()V", null, getThread(), false); //$NON-NLS-1$
			restoreLocals(codeSnippetInstance);
			
			// now retrieve the description of the result
			IVariable[] fields = codeSnippetInstance.getVariables();
			IJavaVariable resultValue = null;
			IJavaVariable resultType = null;
			for (int i = 0; i < fields.length; i++) {
				if (fields[i].getName().equals(RESULT_TYPE_FIELD)) {
					resultType = (IJavaVariable)fields[i];
				}
				if (fields[i].getName().equals(RESULT_VALUE_FIELD)) {
					resultValue = (IJavaVariable)fields[i];
				}
			}
			IJavaValue result = convertResult((IJavaClassObject)resultType.getValue(), (IJavaValue)resultValue.getValue());
			getResult().setValue(result);
		} catch (DebugException e) {
			getResult().setException(e);
			
			Throwable underlyingException = e.getStatus().getException();
			if (underlyingException instanceof InvocationException) {
				ObjectReference theException = ((InvocationException)underlyingException).exception();
				if (theException != null) {
					try {
						try {
							IJavaObject v = (IJavaObject)JDIValue.createValue((JDIDebugTarget)getDebugTarget(), theException);
							v.sendMessage("printStackTrace", "()V", null, getThread(), false); //$NON-NLS-2$ //$NON-NLS-1$
						} catch (DebugException de) {
							JDIDebugPlugin.log(de);
						}
					} catch (RuntimeException re) {
						JDIDebugPlugin.log(re);
					}
				}
			}
		}
		
	}
	
	/**
	 * Initializes the value of instance variables in the 
	 * 'code snippet object' that are used as placeholders
	 * for locals and 'this' in the current stack frame.
	 * 
	 * @param object instance of code snippet class that will
	 * 	be run
	 * @exception DebugException if an exception is thrown
	 *  accessing the given object
	 */
	protected void initializeLocals(IJavaObject object) throws DebugException {
		IJavaVariable[] locals = null;
		IJavaObject thisObject = getThis();
		if (getStackFrame() != null) {
			locals = getStackFrame().getLocalVariables();
		}
		if (locals != null) {
			for (int i = 0; i < locals.length; i++) {
				IJavaVariable local = locals[i];
				IJavaVariable field = object.getField(LOCAL_VAR_PREFIX + local.getName(), false);
				// internal error if field is not found
				if (field == null) {
					throw new DebugException(
						new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(),
						DebugException.REQUEST_FAILED, EvaluationMessages.getString("LocalEvaluationEngine.Evaluation_failed_-_unable_to_initialize_local_variables._4"), null) //$NON-NLS-1$
					);
				} else {
					field.setValue(local.getValue());
				}
			}
		}
		if (thisObject != null) {
			IJavaVariable field = object.getField(DELEGATE_THIS, false);
			// internal error if field is not found
			if (field == null) {
				throw new DebugException(
					new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(),
					DebugException.REQUEST_FAILED, EvaluationMessages.getString("LocalEvaluationEngine.Evaluation_failed_-_unable_to_initialize___this___context._5"), null) //$NON-NLS-1$
				);				
			} else {
				field.setValue(thisObject);
			}
		}
	}
	
	/**
	 * Restores the value local variables from the instance
	 * variables in the  'code snippet object' that are used
	 * as placeholders for locals in the current stack frame.
	 * 
	 * @param object instance of code snippet class that was
	 *   run
	 * @exception DebugException if an exception is thrown
	 *  accessing the given object
	 */
	protected void restoreLocals(IJavaObject object) throws DebugException {
		IJavaVariable[] locals = null;
		if (getStackFrame() != null) {
			locals = getStackFrame().getLocalVariables();
		}
		if (locals != null) {
			for (int i = 0; i < locals.length; i++) {
				IJavaVariable local = locals[i];
				IJavaVariable field = object.getField(LOCAL_VAR_PREFIX + local.getName(), false);
				// internal error if field is not found
				if (field == null) {
					throw new DebugException(
						new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(),
						DebugException.REQUEST_FAILED, EvaluationMessages.getString("LocalEvaluationEngine.Evaluation_failed_-_unable_to_initialize_local_variables._6"), null) //$NON-NLS-1$
					);
				} else {
					local.setValue(field.getValue());
				}
			}
		}
	}

	/**
	 * @see ICodeSnippetRequestor#acceptProblem(IMarker, String, int)
	 */
	public void acceptProblem(IMarker problemMarker, String fragmentSource, int fragmentKind) {
		if (problemMarker.getAttribute(IMarker.SEVERITY, -1) != IMarker.SEVERITY_ERROR) {
			return;
		}
		Message message= new Message(problemMarker.getAttribute(IMarker.MESSAGE, ""), problemMarker.getAttribute(IMarker.CHAR_START, 0)); //$NON-NLS-1$
		getResult().addError(message);
	}
	
	/**
	 * @see IEvaluationEngine#getDebugTarget()
	 */
	public IJavaDebugTarget getDebugTarget() {
		return fDebugTarget;
	}

	/**
	 * Sets the debug target in which snippets are executed.
	 * 
	 * @param debugTarget the debug target in which snippets are executed
	 */
	private void setDebugTarget(IJavaDebugTarget debugTarget) {
		fDebugTarget = debugTarget;
	}

	/**
	 * @see IEvaluationEngine#getJavaProject()
	 */
	public IJavaProject getJavaProject() {
		return fJavaProject;
	}

	/**
	 * Sets the Java project in which snippets are compiled.
	 * 
	 * @param javaProject the Java project in which snippets are compiled
	 */
	private void setJavaProject(IJavaProject javaProject) {
		fJavaProject = javaProject;
	}

	/**
	 * Returns the directory in which snippet class files are deployed.
	 * 
	 * @return the directory in which snippet class files are deployed.
	 */
	public File getOutputDirectory() {
		return fOutputDirectory;
	}

	/**
	 * Sets the directory in which snippet class files are
	 * deployed.
	 * 
	 * @param outputDirectory location to deploy snippet class files
	 */
	private void setOutputDirectory(File outputDirectory) {
		fOutputDirectory = outputDirectory;
	}

	/**
	 * @see IClassFileEvaluationEngine#evaluate(String, IJavaThread, IEvaluationListener)
	 */
	public void evaluate(String snippet, IJavaThread thread, IEvaluationListener listener) throws DebugException {
			checkDisposed();
			checkEvaluating();
			try {
				evaluationStarted();
				setListener(listener);
				setResult(new EvaluationResult(this, snippet, thread));
				checkThread();
				// no receiver/stack frame context
				setThis(null);
				setLocalVariableNames(EMPTY_STRING_ARRAY);
				setLocalVariableTypeNames(EMPTY_STRING_ARRAY);
				setLocalVariableModifiers(EMPTY_INT_ARRAY);
				
				// do the evaluation in a different thread
				Runnable r = new Runnable() {
					public void run() {
						try {
							LocalEvaluationEngine.this.getEvaluationContext().
								evaluateCodeSnippet(LocalEvaluationEngine.this.getSnippet(),
									LocalEvaluationEngine.this, null);
						} catch (JavaModelException e) {
							LocalEvaluationEngine.this.getResult().setException(new DebugException(e.getStatus()));
						}
						LocalEvaluationEngine.this.evaluationComplete();
					}
				};
				
				Thread t = new Thread(r);
				t.start();
			} catch (DebugException d) {
				evaluationAborted();
				throw d;
			}
			
	}

	/**
	 * @see IEvaluationEngine#evaluate(String, IJavaStackFrame, IEvaluationListener, int)
	 */
	public void evaluate(String snippet, IJavaStackFrame frame, IEvaluationListener listener, int evaluationDetail) throws DebugException {
			checkDisposed();
			checkEvaluating();
			try {
				evaluationStarted();
				setListener(listener);
				setStackFrame(frame);
				setResult(new EvaluationResult(this, snippet, (IJavaThread)frame.getThread()));
				checkThread();
				
				// set up local variables and 'this' context for evaluation
				IJavaVariable[] locals = frame.getLocalVariables();
				
				List typeNames = new ArrayList(locals.length);
				List varNames = new ArrayList(locals.length);
	
				for (int i = 0; i < locals.length; i++) {
					IJavaVariable var = locals[i];
					String typeName = getTranslatedTypeName(var.getReferenceTypeName());
					if (typeName != null) {
						typeNames.add(typeName);
						varNames.add(var.getName());
					}
				}
				
				setLocalVariableTypeNames((String[])typeNames.toArray(new String[typeNames.size()]));
				setLocalVariableNames((String[])varNames.toArray(new String[varNames.size()]));
				int[] modifiers = new int[typeNames.size()];
				// cannot determine if local is final, so specify as default
				Arrays.fill(modifiers, 0);
				setLocalVariableModifiers(modifiers);
				setThis(frame.getThis());
				
				final boolean isStatic = frame.isStatic();				
				final boolean isConstructor = frame.isConstructor();
				final IType receivingType = getReceivingType(frame);
				
				// do the evaluation in a different thread
				Runnable r = new Runnable() {
					public void run() {
						try {
							LocalEvaluationEngine.this.getEvaluationContext().
								evaluateCodeSnippet(
								LocalEvaluationEngine.this.getSnippet(),
								LocalEvaluationEngine.this.getLocalVariableTypeNames(),
								LocalEvaluationEngine.this.getLocalVariableNames(),
								LocalEvaluationEngine.this.getLocalVariableModifiers(),
								receivingType,
								isStatic,
								isConstructor,
								LocalEvaluationEngine.this,
								null);
						} catch (JavaModelException e) {
							LocalEvaluationEngine.this.getResult().setException(new DebugException(e.getStatus()));
						}
						LocalEvaluationEngine.this.evaluationComplete();
					}
				};
				
				Thread t = new Thread(r);
				t.start();
			} catch (DebugException d) {
				evaluationAborted();
				throw d;
			}				
	}
	
	/**
	 * @see IEvaluationEngine#evaluate(String, IJavaObject, IJavaThread, IEvaluationListener, int)
	 */
	public void evaluate(String snippet, IJavaObject thisContext, IJavaThread thread, IEvaluationListener listener, int evaluationDetail) throws DebugException {
			checkDisposed();
			checkEvaluating();
			try {
				evaluationStarted();
				setListener(listener);
				setResult(new EvaluationResult(this, snippet, thread));
				checkThread();
							
				// no locals			
				setLocalVariableTypeNames(new String[0]);
				setLocalVariableNames(new String[0]);
				setLocalVariableModifiers(new int[0]);
				
				setThis(thisContext);
				
				final boolean isStatic = false;
				final boolean isConstructor = false;
				final IType receivingType = getReceivingType(thisContext);
				
				// do the evaluation in a different thread
				Runnable r = new Runnable() {
					public void run() {
						try {
							LocalEvaluationEngine.this.getEvaluationContext().
								evaluateCodeSnippet(
								LocalEvaluationEngine.this.getSnippet(),
								LocalEvaluationEngine.this.getLocalVariableTypeNames(),
								LocalEvaluationEngine.this.getLocalVariableNames(),
								LocalEvaluationEngine.this.getLocalVariableModifiers(),
								receivingType,
								isStatic,
								isConstructor,
								LocalEvaluationEngine.this,
								null);
						} catch (JavaModelException e) {
							LocalEvaluationEngine.this.getResult().setException(new DebugException(e.getStatus()));
						}
						LocalEvaluationEngine.this.evaluationComplete();
					}
				};
				
				Thread t = new Thread(r);
				t.start();
			} catch (DebugException d) {
				evaluationAborted();
				throw d;
			}				
	}
	
	/**
	 * Throws an exception if this engine has already been 
	 * disposed.
	 * 
	 * @exception DebugException if this engine has been disposed
	 */
	protected void checkDisposed() throws DebugException {
		if (isDisposed()) {
			throw new DebugException(
				new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(),
				DebugException.REQUEST_FAILED, EvaluationMessages.getString("LocalEvaluationEngine.Evaluation_failed_-_evaluation_context_has_been_disposed._7"), null) //$NON-NLS-1$
			);
		}
	}
	
	/**
	 * Throws an exception if this engine is already in an
	 * evaluation.
	 * 
	 * @exception DebugException if this engine is currently
	 *  performing an evaluation
	 */
	protected void checkEvaluating() throws DebugException {
		if (isEvaluating()) {
			throw new DebugException(
				new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(),
				DebugException.REQUEST_FAILED, "Cannot perform nested evaluations.", null) //$NON-NLS-1$
			);
		}
	}	
	
	/**
	 * Throws an exception if this engine's current evaluation
	 * thread is not suspended.
	 * 
	 * @exception DebugException if this engine's current evaluation
	 *  thread is not suspended
	 */
	protected void checkThread() throws DebugException {
		if (!getThread().isSuspended()) {
			throw new DebugException(
				new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(),
				DebugException.REQUEST_FAILED, EvaluationMessages.getString("LocalEvaluationEngine.Evaluation_failed_-_evaluation_thread_must_be_suspended._8"), null) //$NON-NLS-1$
			);
		}
	}	

	/**
	 * Deletes deployed class files, and clears state.
	 * 
	 * @see IEvaluationEngine#dispose()
	 */
	public void dispose() {
		fDisposed = true;
		if (isEvaluating()) {
			// cannot dispose if in an evalutaion, must
			// wait for evaluation to complete
			return;
		}
		List snippetFiles = getSnippetFiles();
		Iterator iter = snippetFiles.iterator();
		while (iter.hasNext()) {
			File file = (File)iter.next();
			if (file.exists() && !file.delete()) {
				JDIDebugPlugin.log(
					new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(), DebugException.REQUEST_FAILED, 
						MessageFormat.format(EvaluationMessages.getString("LocalEvaluationEngine.Unable_to_delete_temporary_evaluation_class_file_{0}_1"), new String[] {file.getAbsolutePath()}), null) //$NON-NLS-1$
				);				
			}
		}
		List directories = getDirectories();
		// remove directories in bottom up order
		int i = directories.size() - 1;
		while (i >= 0) {
			File dir = (File)directories.get(i);
			if (dir.exists() && !dir.delete()) {
				JDIDebugPlugin.log(
					new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(), DebugException.REQUEST_FAILED, 
						MessageFormat.format(EvaluationMessages.getString("LocalEvaluationEngine.Unable_to_delete_temporary_evaluation_directory_{0}_2"), new String[] {dir.getAbsolutePath()}), null) //$NON-NLS-1$
				);
			}
			i--;
		}
		reset();
		setJavaProject(null);
		setDebugTarget(null);
		setOutputDirectory(null);
		setResult(null);
		setEvaluationContext(null);
	}
	

	/**
	 * Resets this engine for another evaluation.
	 */
	private void reset() {
		setThis(null);
		setStackFrame(null);
		setListener(null);
	}
	
	/**
	 * Returns the listener to notify when the current
	 * evaluation is complete.
	 * 
	 * @return the listener to notify when the current
	 * evaluation is complete
	 */
	protected IEvaluationListener getListener() {
		return fListener;
	}

	/**
	 * Sets the listener to notify when the current
	 * evaluation is complete.
	 * 
	 * @param listener the listener to notify when the current
	 *  evaluation is complete
	 */
	private void setListener(IEvaluationListener listener) {
		fListener = listener;
	}

	/**
	 * Returns the stack frame context for the current
	 * evaluation, or <code>null</code> if none.
	 * 
	 * @return the stack frame context for the current
	 *  evaluation, or <code>null</code> if none
	 */
	protected IJavaStackFrame getStackFrame() {
		return fStackFrame;
	}

	/**
	 * Sets the stack frame context for the current evaluation.
	 * 
	 * @param stackFrame stack frame context or <code>null</code>
	 *   if none
	 */
	private void setStackFrame(IJavaStackFrame stackFrame) {
		fStackFrame = stackFrame;
	}

	/**
	 * Returns the thread in which the current evaluation is
	 * to be executed.
	 * 
	 * @return the thread in which the current evaluation is
	 *  to be executed
	 */
	protected IJavaThread getThread() {
		return getResult().getThread();
	}
	
	/**
	 * Returns the code snippet being evaluated.
	 * 
	 * @return the code snippet being evaluated.
	 */
	protected String getSnippet() {
		return getResult().getSnippet();
	}	

	/**
	 * Returns the current evaluation result.
	 * 
	 * @return the current evaluation result
	 */
	protected EvaluationResult getResult() {
		return fResult;
	}

	/**
	 * Sets the current evaluation result.
	 * 
	 * @param result the current evaluation result
	 */
	private void setResult(EvaluationResult result) {
		fResult = result;
	}
	
	/**
	 * Deploys the given class files to this engine's
	 * output location, and adds the files to this
	 * engines list of temporary files to be deleted
	 * when disposed.
	 *
	 * @exception DebugException if this fails due to a
	 * lower level exception.
	 */
	protected void deploy(final byte[][] classFiles, final String[][] classFileNames) throws DebugException {

		// create the files in a workspace runnable
		IWorkspace workspace= getJavaProject().getProject().getWorkspace();
		IWorkspaceRunnable runnable= new IWorkspaceRunnable() {
				public void run(IProgressMonitor monitor) throws CoreException {					
					for (int i = 0; i < classFiles.length; i++) {
						String[] compoundName = classFileNames[i];
						//create required folders
						File dir = LocalEvaluationEngine.this.getOutputDirectory();
						try {
							String pkgDirName = dir.getCanonicalPath();
							for (int j = 0; j < (compoundName.length - 1); j++) {
								pkgDirName += File.separator +  compoundName[j];
								File pkgDir = new File(pkgDirName);
								if (!pkgDir.exists()) {
									pkgDir.mkdir();
									addDirectory(pkgDir);
								}
							}
							String name = compoundName[compoundName.length - 1] + ".class"; //$NON-NLS-1$
							File classFile = new File(pkgDirName + File.separator + name);
							if (!classFile.exists()) {
								classFile.createNewFile();
							}
							FileOutputStream stream = new FileOutputStream(classFile);
							stream.write(classFiles[i]);
							stream.close();
							LocalEvaluationEngine.this.addSnippetFile(classFile);
						} catch (IOException e) {
							throw new DebugException(
								new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(), DebugException.REQUEST_FAILED, 
									MessageFormat.format(EvaluationMessages.getString("LocalEvaluationEngine.{0}_occurred_deploying_class_file_for_evaluation_9"), new String[] {e.toString()}), e) //$NON-NLS-1$
							);
						}
					}	
				}
			};
		try {	
			workspace.run(runnable, null);				
		} catch (CoreException e) {
			throw new DebugException(e.getStatus());
		}
	}	
	
	/**
	 * Adds the given file to this engine's collection 
	 * of deployed snippet class files, which are to
	 * be deleted when this engine is diposed.
	 * 
	 * @param File snippet class file
	 */
	private void addSnippetFile(File file) {
		if (fSnippetFiles == null) {
			fSnippetFiles = new ArrayList();
		}
		fSnippetFiles.add(file);
	}
	
	/**
	 * Adds the given file to this engine's collection 
	 * of cerated directories, which are to
	 * be deleted when this engine is diposed.
	 * 
	 * @param file directory created for class file deployment
	 */
	private void addDirectory(File file) {
		if (fDirectories == null) {
			fDirectories = new ArrayList();
		}
		fDirectories.add(file);
	}	

	/**
	 * Returns an evaluation context for this evaluation
	 * engine. An evaluation context is associted with a
	 * specific Java project. The evaluation context is
	 * created lazily on the first access.
	 *  
	 * @return evaluation context
	 */
	protected IEvaluationContext getEvaluationContext() {
		if (fEvaluationContext == null) {
			fEvaluationContext = getJavaProject().newEvaluationContext();
		}
		return fEvaluationContext;
	}
	
	/**
	 * Sets the evaluation context for this evaluation
	 * engine.
	 *  
	 * @param context evaluation context
	 */
	private void setEvaluationContext(IEvaluationContext context) {
		fEvaluationContext = context;
	}
	

	/**
	 * Returns a collection of snippet class file deployed by
	 * this evaluation engine, possibly empty.
	 * 
	 * @return deployed class files
	 */
	protected List getSnippetFiles() {
		if (fSnippetFiles == null) {
			return Collections.EMPTY_LIST;
		} else {
			return fSnippetFiles;
		}
	}
	
	/**
	 * Returns a collection of directories created by
	 * this evaluation engine, possibly empty.
	 * 
	 * @return directories created when deploying class files
	 */
	protected List getDirectories() {
		if (fDirectories == null) {
			return Collections.EMPTY_LIST;
		} else {
			return fDirectories;
		}
	}	
	
	/**
	 * Returns whether this evaluation engine has been
	 * disposed.
	 * 
	 * @return whether this evaluation engine has been
	 *  disposed
	 */
	protected boolean isDisposed() {
		return fDisposed;
	}
	
	/**
	 * The evaluation is complete. Notify the current listener
	 * and reset for the next evaluation.
	 */
	protected void evaluationComplete() {
		getListener().evaluationComplete(getResult());
		evaluationEnded();
		reset();
		if (isDisposed()) {
			// if the engine was disposed during an evaluation
			// do the cleanup now
			dispose();
		}				
	}
	
	/**
	 * Increments the evaluation counter.
	 */
	private void evaluationStarted() {
		fEvaluationCount++;
	}
	
	/**
	 * Decrements the evaluation counter.
	 */
	private void evaluationEnded() {
		if (fEvaluationCount > 0) {
			fEvaluationCount--;
		}
	}	
	
	/**
	 * Returns whether this engine is currently in the
	 * midst of an evaluation.
	 */
	protected boolean isEvaluating() {
		return fEvaluationCount > 0;
	}
	
	/**
	 * Called when an evaluation is aborted due to an
	 * exception. Decrements the evalution count, and
	 * disposes this engine if the target VM disconnected
	 * or terminated during the evaluation attempt.
	 */
	private void evaluationAborted() {
		evaluationEnded();
		if (isDisposed()) {
			// if the engine was disposed during an evaluation
			// do the cleanup now
			dispose();
		}		
	}
	
	/**
	 * Constructs and returns a new instance of the specified
	 * class on the  target VM.
	 * 
	 * @param className fully qualified class name
	 * @return a new instance on the target, as an <code>IJavaValue</code>
	 * @exception DebugException if creation fails
	 */
	protected IJavaObject newInstance(String className) throws DebugException {
		IJavaObject object = null;
		IJavaClassType clazz = null;
		IJavaType[] types = getDebugTarget().getJavaTypes(className);
		if (types != null && types.length > 0) {
			clazz = (IJavaClassType)types[0];
		}
		if (clazz == null) {
			// The class is not loaded on the target VM.
			// Force the load of the class.
			types = getDebugTarget().getJavaTypes("java.lang.Class"); //$NON-NLS-1$
			IJavaClassType classClass = null;
			if (types != null && types.length > 0) {
				classClass = (IJavaClassType)types[0]; 
			}
			if (classClass == null) {
				// unable to load the class
				throw new DebugException(
					new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(),
					DebugException.REQUEST_FAILED, EvaluationMessages.getString("LocalEvaluationEngine.Evaluation_failed_-_unable_to_instantiate_code_snippet_class._11"), null) //$NON-NLS-1$
				);				
			}
			IJavaValue[] args = new IJavaValue[] {getDebugTarget().newValue(className)};
			IJavaObject classObject = (IJavaObject)classClass.sendMessage("forName", "(Ljava/lang/String;)Ljava/lang/Class;", args, getThread()); //$NON-NLS-2$ //$NON-NLS-1$
			object = (IJavaObject)classObject.sendMessage("newInstance", "()Ljava/lang/Object;", null, getThread(), false); //$NON-NLS-2$ //$NON-NLS-1$
		} else {
			object = (IJavaObject)clazz.newInstance("<init>", null, getThread()); //$NON-NLS-1$
		}
		return object;
	}
	
	/**
	 * Interpretts and returns the result of the running the snippet
	 * class file. The type of the result is described by an instance of
	 * <code>java.lang.Class</code>. The value is interpretted based
	 * on the result type.
	 * <p>
	 * Objects as well as primitve data types (boolean, int, etc.),
	 * have class objects, which are created by the VM. If the class object
	 * represents a primitive data type, then the associated value
	 * is stored in an instance of its "object" class. For example, when 
	 * the result type is the class object for <code>int</code>, the result
	 * object is an instance of <code>java.lang.Integer</code>, and the
	 * actual <code>int</code> is stored in the </code>intValue()</code>.
	 * When the result type is the class object for <code>java.lang.Integer</code>
	 * the result object is an instance of <code>java.lang.Integer</code>,
	 * to be interpretted as a <ocde>java.lang.Integer</code>.
	 * </p>
	 * 
	 * @param resultType the class of the result
	 * @param resultValue the value of ther result, to be interpretted
	 *  based on resultType
	 * @return the result of running the code snipped class file
	 */
	protected IJavaValue convertResult(IJavaClassObject resultType, IJavaValue result) throws DebugException {
		if (resultType == null) {
			// there was an exception or compilation problem - no result
			return null;
		}

		// check the type of the result - if a primitive type, convert it
		String sig = resultType.getInstanceType().getSignature();
		if (sig.equals("V") || sig.equals("Lvoid;")) { //$NON-NLS-2$ //$NON-NLS-1$
			// void
			return getDebugTarget().voidValue();
		}

		if (result.getJavaType() == null) {
			// null result
			return result;
		}
		
		if (sig.length() == 1) {
			// primitive type - find the instance variable with the
			// signature of the result type we are looking for
			IVariable[] vars = result.getVariables();
			IJavaVariable var = null;
			for (int i = 0; i < vars.length; i++) {
				IJavaVariable jv = (IJavaVariable)vars[i];
				if (!jv.isStatic() && jv.getSignature().equals(sig)) {
					var = jv;
					break;
				}
			}
			if (var != null) {
				return (IJavaValue)var.getValue();
			}
		} else {
			// an object
			return result;
		}
		throw new DebugException(
			new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(),
			DebugException.REQUEST_FAILED, EvaluationMessages.getString("LocalEvaluationEngine.Evaluation_failed_-_internal_error_retreiving_result._17"), null) //$NON-NLS-1$
		);
	}
	
	/**
	 * Returns the modifiers of the local variables
	 * visible in this evaluation, possibly empty.
	 * 
	 * @return array of modifiers
	 */
	private int[] getLocalVariableModifiers() {
		return fLocalVariableModifiers;
	}

	/**
	 * Sets the modifiers of the local variables
	 * visible in this evaluation, possibly empty.
	 * 
	 * @param localVariableModifiers array of modifiers
	 */
	private void setLocalVariableModifiers(int[] localVariableModifiers) {
		fLocalVariableModifiers = localVariableModifiers;
	}
	
	/**
	 * Returns the names of the local variables
	 * visible in this evaluation, possibly empty.
	 * 
	 * @param array of names
	 */	
	private String[] getLocalVariableNames() {
		return fLocalVariableNames;
	}
	
	/**
	 * Sets the names of the local variables
	 * visible in this evaluation, possibly empty.
	 * 
	 * @param localVariableNames array of names
	 */
	private void setLocalVariableNames(String[] localVariableNames) {
		fLocalVariableNames = localVariableNames;
	}

	/**
	 * Returns the type names of the local variables
	 * visible in this evaluation, possibly empty.
	 * 
	 * @param array of type names
	 */
	private String[] getLocalVariableTypeNames() {
		return fLocalVariableTypeNames;
	}

	/**
	 * Sets the type names of the local variables
	 * visible in this evaluation, possibly empty.
	 * 
	 * @param localVariableTypeNames array of type names
	 */
	private void setLocalVariableTypeNames(String[] localVariableTypeNames) {
		fLocalVariableTypeNames = localVariableTypeNames;
	}	
	
	/**
	 * Sets the receiver context for the associated evaluation,
	 * possibly <code>null</code> if the evaluation is
	 * in the context of a static method or there is
	 * no object context.
	 * 
	 * @param thisObject the receiver content of the
	 *  associated evaluation, or <code>null</code>
	 */
	private void setThis(IJavaObject thisObject) {
		fThis = thisObject;
	}
	
	/**
	 * Returns the receiver context for the associated evaluation,
	 * or <code>null</code> if the evaluation is
	 * in the context of a static method or there is
	 * no object context.
	 * 
	 * @return the receiver context of the associated
	 *  evaluation or <code>null</code>
	 */
	private IJavaObject getThis() {
		return fThis;
	}
	
	/**
	 * Returns a copy of the type name with '$' replaced by
	 * '.', or returns <code>null</code> if the given type
	 * name refers to an anonymous inner class. 
	 * 
	 * @param typeName a fully qualified type name
	 * @return a copy of the type name with '$' replaced by
	 * '.', or returns <code>null</code> if the given type
	 * name refers to an anonymous inner class.
	 */
	protected String getTranslatedTypeName(String typeName) {
		int index = typeName.lastIndexOf('$');
		if (index == -1) {
			return typeName;
		}
		if (index + 1 > typeName.length()) {
			// invalid name
			return typeName;
		}
		String last = typeName.substring(index + 1);
		try {
			Integer.parseInt(last);
			return null;
		} catch (NumberFormatException e) {
			return typeName.replace('$', '.');
		}
	}


	/**
	 * Returns the receiving type of the the given stack frame.
	 * 
	 * @return receiving type
	 * @exception DebugException if:<ul>
	 * <li>A failure occurs while accessing attributes of 
	 *  the stack frame</li>
	 * <li>the resolved type is an inner type</li>
	 * <li>unable to resolve a type</li>
	 * </ul>
	 */
	private IType getReceivingType(IJavaStackFrame frame) throws DebugException {
		String typeName = frame.getReceivingTypeName();
		String sourceName =frame.getSourceName();
		if (sourceName == null || !typeName.equals(frame.getDeclaringTypeName())) {
			// if there is no debug attribute or the declaring type is not the
			// same as the receiving type, we must guess at the receiver's source
			// file
			int dollarIndex= typeName.indexOf('$');
			if (dollarIndex >= 0) {
				typeName= typeName.substring(0, dollarIndex);
			}
			typeName = typeName.replace('.', IPath.SEPARATOR);
			typeName+= ".java";			 //$NON-NLS-1$
		} else {
			int index = typeName.lastIndexOf('.');
			if (index >= 0) {
				typeName = typeName.substring(0, index + 1);
				typeName = typeName.replace('.', IPath.SEPARATOR);
			} else {
				typeName = ""; //$NON-NLS-1$
			}
			typeName+=sourceName;
		}

		IPath sourcePath =  new Path(typeName);
		
		IType type = null;
		try {
			IJavaElement result = getJavaProject().findElement(sourcePath);
			String[] typeNames = getNestedTypeNames(frame.getReceivingTypeName());
			if (result != null) {
				if (result instanceof IClassFile) {
					type = ((IClassFile)result).getType();
				} else if (result instanceof ICompilationUnit) {
					type = ((ICompilationUnit)result).getType(typeNames[0]);
				}
			}
			for (int i = 1; i < typeNames.length; i++) {
				type = type.getType(typeNames[i]);
			}
		} catch (JavaModelException e) {
			throw new DebugException(e.getStatus());
		}
		
		if (type == null) {
			throw new DebugException(
				new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(),
				DebugException.REQUEST_FAILED, EvaluationMessages.getString("LocalEvaluationEngine.Evaluation_failed_-_unable_to_determine_receiving_type_context._18"), null) //$NON-NLS-1$
			);
		}
		
		if (type.getDeclaringType() != null) {
			throw new DebugException(
				new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(),
				DebugException.REQUEST_FAILED, EvaluationMessages.getString("LocalEvaluationEngine.Evaluation_in_context_of_inner_type_not_supported._19"), null) //$NON-NLS-1$
			);
		}	
		
		return type;	
	}
	
	/**
	 * Returns the type of the the given object.
	 * 
	 * @return type
	 * @exception DebugException if:<ul>
	 * <li>A failure occurs while accessing attributes of 
	 *  the object</li>
	 * </ul>
	 */
	private IType getReceivingType(IJavaObject object) throws DebugException {
		String typeName = object.getJavaType().getName();
		// we must guess at the receiver's source file name
		int dollarIndex= typeName.indexOf('$');
		if (dollarIndex >= 0) {
			typeName= typeName.substring(0, dollarIndex);
		}
		return getType(typeName);
	}

	/**
	 * Returns the type associated with the specified
	 * name in this evaluation engine's associated Java project.
	 * 
	 * @param typeName fully qualified name of type, for
	 *  example, <code>java.lang.String</code>
	 * @return main type associated with source file
	 * @exception DebugException if:<ul>
	 * <li>the resolved type is an inner type</li>
	 * <li>unable to resolve a type</li>
	 * <li>a lower level Java exception occurs</li>
	 * </ul>
	 */
	private IType getType(String typeName) throws DebugException {
		String path = typeName.replace('.', IPath.SEPARATOR);
		path+= ".java";			 //$NON-NLS-1$
		IPath sourcePath =  new Path(path);
		
		IType type = null;
		try {
			IJavaElement result = getJavaProject().findElement(sourcePath);
			String[] typeNames = getNestedTypeNames(typeName);
			if (result != null) {
				if (result instanceof IClassFile) {
					type = ((IClassFile)result).getType();
				} else if (result instanceof ICompilationUnit) {
					type = ((ICompilationUnit)result).getType(typeNames[0]);
				}
			}
			for (int i = 1; i < typeNames.length; i++) {
				type = type.getType(typeNames[i]);
			}
		} catch (JavaModelException e) {
			throw new DebugException(e.getStatus());
		}
		
		if (type == null) {
			throw new DebugException(
				new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(),
				DebugException.REQUEST_FAILED, EvaluationMessages.getString("LocalEvaluationEngine.Evaluation_failed_-_unable_to_determine_receiving_type_context._18"), null) //$NON-NLS-1$
			);
		}
		
		if (type.getParent() instanceof IType) {
			throw new DebugException(
				new Status(IStatus.ERROR, JDIDebugModel.getPluginIdentifier(),
				DebugException.REQUEST_FAILED, EvaluationMessages.getString("LocalEvaluationEngine.Evaluation_in_context_of_inner_type_not_supported._19"), null) //$NON-NLS-1$
			);
		}	
		
		return type;	
	}	

	/**
	 * Returns an array of simple type names that are
	 * part of the given type's qualified name. For
	 * example, if the given name is <code>x.y.A$B</code>,
	 * an array with <code>["A", "B"]</code> is returned.
	 * 
	 * @param typeName fully qualified type name
	 * @return array of nested type names
	 */
	protected String[] getNestedTypeNames(String typeName) {
		int index = typeName.lastIndexOf('.');
		if (index >= 0) {
			typeName= typeName.substring(index + 1);
		}
		index = typeName.indexOf('$');
		ArrayList list = new ArrayList(1);
		while (index >= 0) {
			list.add(typeName.substring(0, index));
			typeName = typeName.substring(index + 1);
			index = typeName.indexOf('$');
		}
		list.add(typeName);
		return (String[])list.toArray(new String[list.size()]);	
	}
	
	/**
	 * @see IClassFileEvaluationEngine#getImports()
	 */
	public String[] getImports() {
		return getEvaluationContext().getImports();
	}

	/**
	 * @see IClassFileEvaluationEngine#setImports(String[])
	 */
	public void setImports(String[] imports) {
		getEvaluationContext().setImports(imports);
	}
	
	/**
	 * Sets the name of the code snippet to instantiate
	 * to run the current evaluation.
	 * 
	 * @param name the name of the deployed code snippet
	 *  to instantiate and run
	 */
	private void setCodeSnippetClassName(String name) {
		fCodeSnippetClassName = name;
	}

	/**
	 * Returns the name of the code snippet to instantiate
	 * to run the current evaluation.
	 * 
	 * @return the name of the deployed code snippet
	 *  to instantiate and run
	 */	
	protected String getCodeSnippetClassName() {
		return fCodeSnippetClassName;
	}

	/**
	 * @see ICodeSnippetRequestor#isRequestingClassFiles()
	 */
	public boolean isRequestingClassFiles() {
		return true;
	}

}