/*
 *     Eclipse AST Validation, lite framework to validate java code
 *     
 *     Copyright (C) 2013 Atos Worldline or third-party contributors as
 *     indicated by the @author tags or express copyright attribution
 *     statements applied by the authors.
 *     
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License.
 *     
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *     Lesser General Public License for more details.
 *     
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
package net.atos.jdt.ast.validation.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.atos.jdt.ast.validation.engine.internal.Activator;
import net.atos.jdt.ast.validation.engine.internal.ValidationEngineMessages;
import net.atos.jdt.ast.validation.engine.internal.extpt.ASTRulesExtensionPoint;
import net.atos.jdt.ast.validation.engine.rules.AbstractASTRule;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

/**
 * Process that execute validation rules on ICompilationUnits
 * 
 * @author mvanbesien
 * @since 1.0
 */
public class ASTValidationEngine {
	/**
	 * List of Compilation Units to be managed by this process
	 */
	private final Collection<ICompilationUnit> compilationUnits = new ArrayList<ICompilationUnit>();;

	/**
	 * Valid Repositories for this engine
	 */
	private final String[] validRepositories;

	/**
	 * Rules Source, used to retrieve the rules during execution
	 */
	private IASTRulesDataSource dataSource;

	/**
	 * Session to send to the executed rules
	 */
	private final Map<String, Object> session = new HashMap<String, Object>();

	/**
	 * Replaces the session by the one provided as a parameter.
	 * 
	 * @param session
	 * @return
	 */
	public ASTValidationEngine withSession(final Map<String, Object> session) {
		this.session.clear();
		this.session.putAll(session);
		return this;
	}

	/**
	 * Creates new Validation Engine for Compilation Units as from parameters
	 * 
	 * @param compilationUnits
	 * @param validRepositories
	 */
	public ASTValidationEngine(final IASTRulesDataSource dataSource,
			final Collection<ICompilationUnit> compilationUnits, final String... validRepositories) {
		this.compilationUnits.clear();
		this.compilationUnits.addAll(compilationUnits);
		this.validRepositories = validRepositories;
		if (dataSource != null) {
			this.dataSource = dataSource;
		}
	}

	/**
	 * Creates new Validation Engine for Compilation Units as from parameters
	 * 
	 * @param compilationUnits
	 * @param validRepositories
	 */
	public ASTValidationEngine(final Collection<ICompilationUnit> compilationUnits, final String... validRepositories) {
		this.compilationUnits.clear();
		this.compilationUnits.addAll(compilationUnits);
		this.validRepositories = validRepositories;
		this.dataSource = ASTRulesExtensionPoint.getInstance();
	}

	/**
	 * Runs the validation
	 * 
	 * @param monitor
	 * @throws CoreException
	 */
	public void execute(final IProgressMonitor monitor) throws CoreException {
		List<Callable<Void>> tasks = new ArrayList<>();
		for (final ICompilationUnit compilationUnit : this.compilationUnits) {
			tasks.add(() -> {
				this.execute(compilationUnit, monitor);
				return null;
			});
		}
		
		int cores = Runtime.getRuntime().availableProcessors();
		final ExecutorService executor = Executors.newFixedThreadPool(cores);
		try
		{
			executor.invokeAll(tasks);
		}
		catch (InterruptedException e)
		{
			e.printStackTrace();
		}
		finally
		{
			executor.shutdown();
		}
	}

	/**
	 * Runs the validation on one specific compilation unit
	 * 
	 * @param compilationUnit
	 * @param monitor
	 * @throws CoreException
	 */
	private void execute(final ICompilationUnit compilationUnit, final IProgressMonitor monitor) throws CoreException {

		if ((compilationUnit == null) || !compilationUnit.exists()) {
			return;
		}
		
		// In my experience the AST for the compilation unit only needs parsing once (memoize), not for each ruleDescriptor
		// This yeild's a big performance gain
		Supplier<CompilationUnit> parseAST = Suppliers.memoize(() -> {
			final ASTParser parser = ASTParser.newParser(AST.JLS4);
			parser.setSource(compilationUnit);
			parser.setStatementsRecovery(true);
			parser.setResolveBindings(true);
			parser.setBindingsRecovery(false);
			return (CompilationUnit) parser.createAST(new NullProgressMonitor());
		});

		final List<ASTRulesRepository> repositories = this.dataSource.getRepositories(this.validRepositories);
		// At first remove the previous markers
		final IResource resource = compilationUnit.getResource();

		for (final ASTRulesRepository ruleRepository : repositories) {
			resource.deleteMarkers(ruleRepository.getMarkerId(), true, IResource.DEPTH_ZERO);

			for (final ASTRuleDescriptor ruleDescriptor : ruleRepository.getRules(compilationUnit)) {
				monitor.subTask(ValidationEngineMessages.VALIDATING_CU.value(compilationUnit.getElementName(),
						ruleDescriptor.getDescription()));
				final CompilationUnit domCompilationUnit = parseAST.get();
				try {
					final AbstractASTRule rule = ruleDescriptor.getRule();
					rule.setSession(this.session);
					domCompilationUnit.accept(rule);
					for (final ASTValidationProblem problem : rule.getProblems()) {
						problem.toMarker(resource);
					}
				} catch (final Exception e) {
					String ruleId = ruleDescriptor.getRule() != null ? ruleDescriptor
							.getRule().getClass().getName()
							: ruleDescriptor.getId();
					Activator
							.getDefault()
							.getLog()
							.log(new Status(
									IStatus.ERROR,
									Activator.PLUGIN_ID,
									ValidationEngineMessages.EXCEPTION_DURING_RULEEXEC.value(ruleId,
											compilationUnit.getElementName()), e));
					e.printStackTrace();
				}
			}
		}
	}
}
