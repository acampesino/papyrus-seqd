/*****************************************************************************
 * (c) Copyright 2018 Telefonaktiebolaget LM Ericsson
 *
 *    
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Antonio Campesino (Ericsson) - Initial API and implementation
 *
 *****************************************************************************/
package org.eclipse.papyrus.uml.interaction.internal.model.commands;

import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Collectors;

import org.eclipse.emf.common.command.Command;
import org.eclipse.emf.common.command.UnexecutableCommand;
import org.eclipse.papyrus.uml.interaction.graph.GraphPredicates;
import org.eclipse.papyrus.uml.interaction.graph.Vertex;
import org.eclipse.papyrus.uml.interaction.internal.model.impl.MLifelineImpl;
import org.eclipse.papyrus.uml.interaction.model.spi.NoopCommand;
import org.eclipse.uml2.uml.UMLPackage;

/**
 * A horizontal resize operation. It resize the lifeline, keeping its original position and moves all of its
 * dependencies accordingly. It does not reorder any elements in the sequence.
 *
 * @author Antonio Campesino - Ericsson AB
 */
public class ResizeHorizontallyCommand extends ModelCommand<MLifelineImpl> {
	private final int deltaWidth;

	/**
	 * Constructor
	 * 
	 * @param lifeline
	 *            the lifeline to be resized
	 * @param deltaX
	 *            the delta by which the {@code lifeline} is resized.
	 */
	public ResizeHorizontallyCommand(MLifelineImpl lifeline, int deltaWidth) {
		super(lifeline);
		this.deltaWidth = deltaWidth;
	}

	@Override
	protected Command createCommand() {
		Command cmd = NoopCommand.INSTANCE;
		if (deltaWidth == 0) {
			return cmd;
		}

		Vertex vertex = vertex();
		OptionalInt right = layoutHelper().getRight(vertex);
		if (right.isPresent()) {
			cmd = cmd.chain(layoutHelper().setRight(vertex, right.getAsInt() + deltaWidth));
		}

		if (vertex == null) {
			// This would not be a normal situation
			cmd = UnexecutableCommand.INSTANCE;
		} else {
			// All lifelines to the right of the one we're nudging
			List<Vertex> lifelines = vertex.graph().initial().immediateSuccessors()
					.filter(GraphPredicates.isA(UMLPackage.Literals.LIFELINE)).sequential()
					.collect(Collectors.toList());

			// Note that a move left is just a negative move right
			MoveRightVisitor moveRight = new MoveRightVisitor(this, deltaWidth);
			int referencePoint = lifelines.indexOf(vertex);
			List<Vertex> rightLifelines = lifelines.subList(referencePoint + 1, lifelines.size());

			// But are there any lifelines to the right?
			if (!rightLifelines.isEmpty()) {
				// Our lifeline and those to its left do not move
				List<Vertex> leftLifelines = lifelines.subList(0, referencePoint + 1);
				leftLifelines.forEach(moveRight::markVisited);
				// The lifelines to the right of ours move
				rightLifelines.forEach(moveRight::visit);

				// And all following (skipping lifelines already visited or marked as such)
				getGraph().walkAfter(vertex, moveRight);

				cmd = cmd.chain(moveRight.getResult());
			}
		}

		return cmd;
	}
}
