/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.debug.ui.jres;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.eclipse.jdt.debug.ui.launchConfigurations.AbstractVMInstallPage;
import org.eclipse.jdt.internal.debug.ui.JavaDebugImages;
import org.eclipse.jdt.internal.debug.ui.SWTFactory;
import org.eclipse.jdt.internal.launching.StandardVMType;
import org.eclipse.jdt.launching.IVMInstallType;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.VMStandin;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;

/**
 * Wizard page used to select a VM type.
 * 
 * @since 3.4
 */
public class VMTypePage extends WizardPage {
	
	private CheckboxTableViewer fTypesViewer;
	
	private AbstractVMInstallPage fNextPage;
	
	/**
	 * Keep track of pages created, so we can dispose of them.
	 */
	private Set fPages = new HashSet();
	
	/**
	 * Label provider for VM types
	 */
	private class TypeLabelProvider extends LabelProvider {

		/* (non-Javadoc)
		 * @see org.eclipse.jface.viewers.LabelProvider#getText(java.lang.Object)
		 */
		public String getText(Object element) {
			if (element instanceof IVMInstallType) {
				IVMInstallType type = (IVMInstallType) element;
				return type.getName();
			}
			return super.getText(element);
		}
		
	}
	
	/**
	 * Constructs a VM type selection page
	 */
	public VMTypePage() {
		super(JREMessages.VMTypePage_0);
		setDescription(JREMessages.VMTypePage_1);
		setTitle(JREMessages.VMTypePage_2);
	}
	
	
	/* (non-Javadoc)
	 * @see org.eclipse.jface.dialogs.DialogPage#dispose()
	 */
	public void dispose() {
		super.dispose();
		Iterator iterator = fPages.iterator();
		while (iterator.hasNext()) {
			AbstractVMInstallPage page = (AbstractVMInstallPage)iterator.next();
			page.dispose();
		}
	}


	/* (non-Javadoc)
	 * @see org.eclipse.jface.dialogs.IDialogPage#createControl(org.eclipse.swt.widgets.Composite)
	 */
	public void createControl(Composite parent) {
		Composite composite = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout();
		layout.numColumns = 1;
		composite.setLayout(layout);
		composite.setLayoutData(new GridData(GridData.FILL_BOTH));
		
		SWTFactory.createLabel(composite, JREMessages.VMTypePage_3, 1);
		
		fTypesViewer = CheckboxTableViewer.newCheckList(composite, SWT.SINGLE | SWT.BORDER);
		GridData data = new GridData(GridData.FILL_BOTH);
        data.heightHint = 250;
        data.widthHint = 300;
        fTypesViewer.getTable().setLayoutData(data);
        fTypesViewer.setContentProvider(new ArrayContentProvider());
        fTypesViewer.setLabelProvider(new TypeLabelProvider());
		fTypesViewer.setComparator(new ViewerComparator());
		fTypesViewer.addCheckStateListener(new ICheckStateListener() {
			public void checkStateChanged(CheckStateChangedEvent event) {
				if (event.getChecked()) {
					// only check one element
					fTypesViewer.setCheckedElements(new Object[]{event.getElement()});
					setPageComplete(true);
					updateNextPage();
				} else {
					setPageComplete(false);
				}
			}
		});
		fTypesViewer.setInput(JavaRuntime.getVMInstallTypes());
		setControl(composite);
		
		fTypesViewer.setChecked(JavaRuntime.getVMInstallType(StandardVMType.ID_STANDARD_VM_TYPE), true);
		updateNextPage();
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.jface.dialogs.IDialogPage#getImage()
	 */
	public Image getImage() {
		return JavaDebugImages.get(JavaDebugImages.IMG_WIZBAN_LIBRARY);
	}	
	
	/* (non-Javadoc)
	 * @see org.eclipse.jface.wizard.WizardPage#getNextPage()
	 */
	public IWizardPage getNextPage() {
		return fNextPage;
	}
	
	private void updateNextPage() {
		if (isPageComplete()) {
			Object[] checkedElements = fTypesViewer.getCheckedElements();
			if (checkedElements.length == 1) {
				IVMInstallType installType = (IVMInstallType)checkedElements[0];
				AbstractVMInstallPage page = ((VMInstallWizard)getWizard()).getPage(installType);
				page.setWizard(getWizard());
				VMStandin standin = new VMStandin(installType, StandardVMPage.createUniqueId(installType));
				standin.setName(""); //$NON-NLS-1$
				page.setSelection(standin);
				fNextPage = page;
				fPages.add(page);
			}
		}		
	}

}