/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.debug.tests.console;

import java.io.PrintStream;

import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IActionDelegate2;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWorkbenchWindowActionDelegate;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleHyperlink;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.IOConsole;
import org.eclipse.ui.console.IOConsoleOutputStream;
import org.eclipse.ui.console.IPatternMatchHandler;

public class IOConsoleHyperlinkTest implements IActionDelegate2, IWorkbenchWindowActionDelegate {
 
    public void run(IAction action) {
        final IOConsole console = new IOConsole("IO Test Console", DebugUITools.getImageDescriptor(IDebugUIConstants.IMG_ACT_RUN)); //$NON-NLS-1$
        console.setConsoleWidth(17);
        IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
        manager.addConsoles(new IConsole[] { console });

        IPatternMatchHandler listener = new IPatternMatchHandler() {
            public String getPattern() {
                return "1234567890"; //$NON-NLS-1$
            }

            public void matchFound(String text, int offset) {
                try {
                    console.addHyperlink(new MyHyperlink(), offset, text.length());
                } catch (BadLocationException e) {
                    e.printStackTrace();
                }
            }
        };
        
        console.addPatternMatchNotifier(listener);
        IOConsoleOutputStream stream = console.createOutputStream("OUTPUT");
        stream.setFontStyle(SWT.ITALIC | SWT.BOLD);
        final PrintStream out = new PrintStream(stream);
        new Thread(new Runnable() {
            public void run() {
                out.println("Hyperlink -12345678901234567890-");
            }
        }).start();
    }
    
    private class MyHyperlink implements IConsoleHyperlink {
        public void linkEntered() {
            System.out.println("link entered");
        }

        public void linkExited() {
            System.out.println("link exited");
        }

        public void linkActivated() {
            System.out.println("link activated");
        }
    }
    
    
    
    public void init(IAction action) {        
    }

    public void dispose() {        
    }

    public void runWithEvent(IAction action, Event event) {
        run(action);
    }

    
    public void selectionChanged(IAction action, ISelection selection) {
    }

    public void init(IWorkbenchWindow window) {       
    }

}
