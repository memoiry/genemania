/**
 * This file is part of GeneMANIA.
 * Copyright (C) 2008-2011 University of Toronto.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.genemania.plugin.delegates;

import org.genemania.exception.ApplicationException;
import org.genemania.plugin.GeneMania;
import org.genemania.plugin.cytoscape.CytoscapeUtils;
import org.genemania.plugin.model.ViewState;
import org.genemania.plugin.proxies.NodeProxy;
import org.genemania.plugin.selection.NetworkSelectionManager;

public class NodeSelectionDelegate<NETWORK, NODE, EDGE> extends SelectionDelegate<NETWORK, NODE, EDGE> {
	private final NODE node;

	public NodeSelectionDelegate(NODE node, boolean selected, NETWORK network, NetworkSelectionManager<NETWORK, NODE, EDGE> manager, GeneMania<NETWORK, NODE, EDGE> plugin, CytoscapeUtils<NETWORK, NODE, EDGE> cytoscapeUtils) {
		super(selected, network, manager, plugin, cytoscapeUtils);
		this.node = node;
	}
	
	@Override
	protected void handleSelection(ViewState options) throws ApplicationException {
		NodeProxy<NODE> nodeProxy = cytoscapeUtils.getNodeProxy(node, network);
		String name = nodeProxy.getAttribute(CytoscapeUtils.GENE_NAME_ATTRIBUTE, String.class);
		options.setGeneHighlighted(name, selected);
	}
}
