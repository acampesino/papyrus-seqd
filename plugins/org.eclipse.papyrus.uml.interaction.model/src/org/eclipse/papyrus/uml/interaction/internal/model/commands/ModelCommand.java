/*****************************************************************************
 * Copyright (c) 2018 Christian W. Damus and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Christian W. Damus - Initial API and implementation
 *****************************************************************************/

package org.eclipse.papyrus.uml.interaction.internal.model.commands;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

import org.eclipse.emf.common.command.Command;
import org.eclipse.emf.common.command.CommandWrapper;
import org.eclipse.emf.edit.domain.EditingDomain;
import org.eclipse.papyrus.uml.interaction.graph.Graph;
import org.eclipse.papyrus.uml.interaction.graph.Vertex;
import org.eclipse.papyrus.uml.interaction.internal.model.impl.LogicalModelPlugin;
import org.eclipse.papyrus.uml.interaction.internal.model.impl.MElementImpl;
import org.eclipse.papyrus.uml.interaction.internal.model.impl.MInteractionImpl;
import org.eclipse.papyrus.uml.interaction.model.MElement;
import org.eclipse.papyrus.uml.interaction.model.MExecution;
import org.eclipse.papyrus.uml.interaction.model.MExecutionOccurrence;
import org.eclipse.papyrus.uml.interaction.model.MInteraction;
import org.eclipse.papyrus.uml.interaction.model.MMessage;
import org.eclipse.papyrus.uml.interaction.model.MMessageEnd;
import org.eclipse.papyrus.uml.interaction.model.spi.DiagramHelper;
import org.eclipse.papyrus.uml.interaction.model.spi.LayoutHelper;
import org.eclipse.papyrus.uml.interaction.model.spi.SemanticHelper;
import org.eclipse.uml2.uml.Element;
import org.eclipse.uml2.uml.Interaction;

/**
 * Partial implementation of a mutation operation on the logical model.
 *
 * @author Christian W. Damus
 * @param <T>
 *            the target of the operation
 */
public abstract class ModelCommand<T extends MElementImpl<?>> extends CommandWrapper {

	private final T target;

	private final Graph graph;

	/**
	 * Initializes me.
	 * 
	 * @param target
	 *            the logical model element on which I operate
	 */
	public ModelCommand(T target) {
		super();

		MInteractionImpl interaction = target.getInteractionImpl();

		this.target = target;
		this.graph = interaction.getGraph();
	}

	protected final void checkInteraction(MElement<?> element) {
		Interaction interaction = element.getInteraction().getElement();

		if ((interaction == null) || (interaction != target.getInteraction().getElement())) {
			throw new IllegalArgumentException("element is not in the lifeline's interaction"); //$NON-NLS-1$
		}
	}

	protected final T getTarget() {
		return target;
	}

	protected final Graph getGraph() {
		return graph;
	}

	protected final Vertex vertex(Element element) {
		return graph.vertex(element);
	}

	protected final Vertex vertex(MElement<? extends Element> element) {
		return vertex(element.getElement());
	}

	protected final Vertex vertex() {
		return vertex(target);
	}

	protected final EditingDomain getEditingDomain() {
		return target.getEditingDomain();
	}

	protected final SemanticHelper semanticHelper() {
		return LogicalModelPlugin.getInstance().getSemanticHelper(getEditingDomain());
	}

	protected final DiagramHelper diagramHelper() {
		return LogicalModelPlugin.getInstance().getDiagramHelper(getEditingDomain());
	}

	protected final LayoutHelper layoutHelper() {
		return LogicalModelPlugin.getInstance().getLayoutHelper(getEditingDomain());
	}

	protected MElement<?> normalizeFragmentInsertionPoint(MElement<?> insertionPoint) {
		MElement<?> result = insertionPoint;

		if (insertionPoint instanceof MMessage) {
			// Before the message is sent, if it is sent
			MMessage message = (MMessage)insertionPoint;
			Optional<MMessageEnd> end = message.getSend();
			if (!end.isPresent()) {
				end = message.getReceive();
			}
			if (end.isPresent()) {
				result = end.get();
			}
		} else if (insertionPoint instanceof MMessageEnd) {
			MMessageEnd end = (MMessageEnd)insertionPoint;
			if (end.isReceive()) {
				Optional<MMessageEnd> sendEnd = end.getOtherEnd();
				if (sendEnd.isPresent()) {
					/* check if this is a sloped message */
					if (sendEnd.get().getTop().orElse(0) == end.getTop().orElse(0)) {
						/* not a sloped message -> insert before send */
						result = sendEnd.get();
					}
				}
			}
		}

		return result;
	}

	/**
	 * Get the global y-ordered timeline of interaction fragments.
	 * 
	 * @param interaction
	 *            an interaction
	 * @return the totality of its interaction fragments, ordered in time from top to bottom Y position
	 */
	protected static List<MElement<? extends Element>> getTimeline(MInteraction interaction) {
		return interaction.getElement().getFragments().stream().map(interaction::getElement)
				.filter(Optional::isPresent).map(Optional::get) //
				.sorted(compareByTop()) //
				.collect(Collectors.toList());
	}

	private static Comparator<MElement<? extends Element>> compareByTop() {
		return (e1, e2) -> {
			int top1 = e1.getTop().orElse(-1);
			int top2 = e2.getTop().orElse(-1);

			int result = top1 - top2;

			if (result == 0) {
				// Message ends are always ordered send < recv.
				// Message ends always precede executions and their start occurrences
				// Executions always follow their start occurrences
				if (e1 instanceof MMessageEnd) {
					MMessageEnd end1 = (MMessageEnd)e1;
					if (e2 instanceof MMessageEnd) {
						MMessageEnd end2 = (MMessageEnd)e2;
						if (end1.getOwner() == end2.getOwner()) {
							result = end1.isSend() ? -1 : +1;
						}
					} else if (e2 instanceof MExecutionOccurrence) {
						result = -1;
					} else if (e2 instanceof MExecution) {
						result = -1;
					}
				} else if (e1 instanceof MExecutionOccurrence) {
					if (e2 instanceof MMessageEnd) {
						result = +1;
					} else if (e2 instanceof MExecution) {
						result = -1;
					}
				} else if (e1 instanceof MExecution) {
					if (e2 instanceof MMessageEnd) {
						result = +1;
					} else if (e2 instanceof MExecutionOccurrence) {
						result = +1;
					}
				}
			}

			return result;
		};
	}

	/**
	 * Find the interaction fragment in a {@code timeline} before which an interaction fragment should be
	 * inserted that would be at the given absolute Y location.
	 * 
	 * @param timeline
	 *            a time-ordered (from top to bottom in the Y axis) sequence of interaction fragments
	 * @param type
	 *            the type of search key to fake, being the type that is to be inserted
	 * @param yPosition
	 *            the position on the Y axis at which a new interaction fragment is to be inserted
	 * @return the interaction fragment before which to insert the new fragment in the semantic model, or an
	 *         empty optional if the new fragment is to be the last fragment in the interaction
	 */
	protected static Optional<MElement<? extends Element>> getInsertionPoint(
			List<? extends MElement<? extends Element>> timeline,
			Class<? extends MElement<? extends Element>> type, int yPosition) {

		final MElement<? extends Element> searchToken = ySearch(type, yPosition);
		int size = timeline.size();
		int index = Collections.binarySearch(timeline, searchToken, compareByTop());

		if (index >= 0) {
			// Easy case
		} else {
			// The search found an "insertion point", which is where the fragment is before which
			// the new one should be inserted
			index = -index - 1;
		}

		return (index < size)
				// Insert before this one
				? Optional.of(timeline.get(index))
				// Append to the end
				: Optional.empty();
	}

	/**
	 * Obtain a fake logical element representing an interaction fragment having a given {@code top} Y
	 * position to be use as a key for searching an ordered timeline.
	 * 
	 * @param type
	 *            the type of search key to fake, being the type that is to be inserted
	 * @param top
	 *            the Y position being searched, to publish as the "top" of a fake element
	 * @return the search term
	 * @see #getTimeline(MInteraction)
	 * @see #getInsertionPoint(List, int)
	 */
	@SuppressWarnings("unchecked")
	private static <T extends MElement<? extends Element>> T ySearch(Class<T> type, int top) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type },
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "getTop": //$NON-NLS-1$
							return OptionalInt.of(top);
						default:
							return null;
					}
				}));
	}

	@Override
	public Command chain(Command next) {
		return CompoundModelCommand.compose(getEditingDomain(), this, next);
	}
}
