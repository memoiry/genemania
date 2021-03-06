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
package org.genemania.plugin.cytoscape;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.WeakHashMap;

import org.apache.commons.beanutils.BeanUtils;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.MappingJsonFactory;
import org.genemania.domain.Attribute;
import org.genemania.domain.AttributeGroup;
import org.genemania.domain.Gene;
import org.genemania.domain.GeneNamingSource;
import org.genemania.domain.Interaction;
import org.genemania.domain.InteractionNetwork;
import org.genemania.domain.InteractionNetworkGroup;
import org.genemania.domain.Node;
import org.genemania.domain.Tag;
import org.genemania.plugin.LogUtils;
import org.genemania.plugin.NetworkUtils;
import org.genemania.plugin.Strings;
import org.genemania.plugin.data.DataSet;
import org.genemania.plugin.model.AnnotationEntry;
import org.genemania.plugin.model.Group;
import org.genemania.plugin.model.Network;
import org.genemania.plugin.model.SearchResult;
import org.genemania.plugin.model.ViewState;
import org.genemania.plugin.model.ViewStateBuilder;
import org.genemania.plugin.proxies.EdgeProxy;
import org.genemania.plugin.proxies.NetworkProxy;
import org.genemania.plugin.proxies.NodeProxy;

public abstract class AbstractCytoscapeUtils<NETWORK, NODE, EDGE> implements CytoscapeUtils<NETWORK, NODE, EDGE> {
	private static final String EDGE_TYPE_INTERACTION = "interaction"; //$NON-NLS-1$
	
	protected static final double MINIMUM_NODE_SIZE = 10;
	protected static final double MAXIMUM_NODE_SIZE = 40;
	protected static final double MINIMUM_EDGE_WIDTH = 1;
	protected static final double MAXIMUM_EDGE_WIDTH = 6;

	private final Map<EDGE, EdgeProxy<EDGE, NODE>> edgeProxies;
	private final Map<NODE, NodeProxy<NODE>> nodeProxies;
	private final Map<NETWORK, NetworkProxy<NETWORK, NODE, EDGE>> networkProxies;

	private final Map<String, AttributeHandler> attributeHandlerRegistry = createHandlerRegistry();
	
	protected final NetworkUtils networkUtils;

	public AbstractCytoscapeUtils(NetworkUtils networkUtils) {
		this.networkUtils = networkUtils;
		
		edgeProxies = new WeakHashMap<EDGE, EdgeProxy<EDGE, NODE>>();
		nodeProxies = new WeakHashMap<NODE, NodeProxy<NODE>>();
		networkProxies = new WeakHashMap<NETWORK, NetworkProxy<NETWORK, NODE, EDGE>>();
	}
	
	@Override
	public EdgeProxy<EDGE, NODE> getEdgeProxy(EDGE edge, NETWORK network) {
		if (edge == null) {
			return null;
		}
		if (edgeProxies.containsKey(edge)) {
			return edgeProxies.get(edge);
		}
		EdgeProxy<EDGE, NODE> proxy = createEdgeProxy(edge, network);
		edgeProxies.put(edge, proxy);
		return proxy;
	}
	
	@Override
	public NetworkProxy<NETWORK, NODE, EDGE> getNetworkProxy(NETWORK network) {
		if (network == null) {
			return null;
		}
		if (networkProxies.containsKey(network)) {
			return networkProxies.get(network);
		}
		NetworkProxy<NETWORK, NODE, EDGE> proxy = createNetworkProxy(network);
		networkProxies.put(network, proxy);
		return proxy;
	}
	
	@Override
	public NodeProxy<NODE> getNodeProxy(NODE node, NETWORK network) {
		if (node == null) {
			return null;
		}
		if (nodeProxies.containsKey(node)) {
			return nodeProxies.get(node);
		}
		NodeProxy<NODE> proxy = createNodeProxy(node, network);
		nodeProxies.put(node, proxy);
		return proxy;
	}

	protected abstract NodeProxy<NODE> createNodeProxy(NODE node, NETWORK network);
	protected abstract NetworkProxy<NETWORK, NODE, EDGE> createNetworkProxy(NETWORK network);
	protected abstract EdgeProxy<EDGE, NODE> createEdgeProxy(EDGE edge, NETWORK network);
	
	@Override
	@SuppressWarnings("unchecked")
	public void expandAttributes(NETWORK cyNetwork, ViewState options, List<String> attributes) {
		if (attributes.size() == 0) {
			return;
		}
		
		NetworkProxy<NETWORK, NODE, EDGE> networkProxy = getNetworkProxy(cyNetwork);
		for (EDGE edge : networkProxy.getEdges()) {
			EdgeProxy<EDGE, NODE> edgeProxy = getEdgeProxy(edge, cyNetwork);
			String edgeId = edgeProxy.getIdentifier();
			Set<Network<?>> networks = options.getNetworksByEdge(edgeId);
			List<String> networkNames = edgeProxy.getAttribute(NETWORK_NAMES_ATTRIBUTE, List.class);
			
			for (String attribute : attributes) {
				List<Object> values = new ArrayList<Object>();
				AttributeHandler handler = attributeHandlerRegistry.get(attribute);
				for (String networkName : networkNames) {
					InteractionNetwork network = findNetwork(networkName, networks);
					values.add(handler.getValue(network));
				}
				edgeProxy.setAttribute(attribute, values);
			}
		}
	}
	
	private InteractionNetwork findNetwork(String networkName, Set<Network<?>> networks) {
		for (Network<?> network : networks) {
			InteractionNetwork adapted = network.adapt(InteractionNetwork.class);
			if (adapted == null) {
				continue;
			}
			if (adapted.getName().equals(networkName)) {
				return adapted;
			}
		}
		return null;
	}
	
	interface AttributeHandler {
		public abstract Object getValue(InteractionNetwork network);
	}
	
	static class TagAttributeHandler implements AttributeHandler {
		@Override
		public Object getValue(InteractionNetwork network) {
			StringBuilder builder = new StringBuilder();
			for (Tag tag : network.getTags()) {
				if (builder.length() > 0) {
					builder.append("|"); //$NON-NLS-1$
				}
				builder.append(tag.getName());
			}
			return builder.toString();
		}
	}
	
	static class MetadataAttributeHandler implements AttributeHandler {
		private String name;

		public MetadataAttributeHandler(String attributeName) {
			name = attributeName;
		}
		
		public Object getValue(InteractionNetwork network) {
			try {
				return BeanUtils.getProperty(network.getMetadata(), name);
			} catch (IllegalAccessException e) {
				return null;
			} catch (InvocationTargetException e) {
				return null;
			} catch (NoSuchMethodException e) {
				return null;
			}
		}
	}
	
	private Map<String, AttributeHandler> createHandlerRegistry() {
		Map<String, AttributeHandler> map = new HashMap<String, AttributeHandler>();
		map.put(TAGS, new TagAttributeHandler());
		for (String name : new String[] {
			AUTHORS,
			INTERACTION_COUNT,
			PUBMED_ID,
			PROCESSING_DESCRIPTION,
			PUBLICATION_NAME,
			YEAR_PUBLISHED,
			SOURCE,
			SOURCE_URL,
			TITLE,
			URL,
		}) {
			map.put(name, new MetadataAttributeHandler(name));
		}
		return map;
	}
	
	/**
	 * Returns the <code>NODE</code> that corresponds to the given
	 * <code>Node</code>.  If the <code>NODE</code> does not already
	 * exist, a new one is created.
	 * 
	 * @param node
	 * @param preferredSymbol
	 * @return
	 */
	@Override
	public NODE getNode(NETWORK network, Node node, String preferredSymbol) {
		String id = getNodeId(network, node);
		NODE target = getNode(id, network);
		if (target != null) {
			return target;
		}
		
		String name;
		if (preferredSymbol == null) {
			Gene gene = networkUtils.getPreferredGene(node);
			if (gene == null) {
				name = Strings.missingGeneName;
			} else {
				name = gene.getSymbol();
			}
		} else {
			name = preferredSymbol;
		}

		target = createNode(id, network);
		
		NodeProxy<NODE> nodeProxy = getNodeProxy(target, network);
		nodeProxy.setAttribute(GENE_NAME_ATTRIBUTE, name);
		exportSynonyms(nodeProxy, node);
		return target;
	}
	
	protected String getNodeId(NETWORK network, Node node) {
		NetworkProxy<NETWORK, NODE, EDGE> proxy = getNetworkProxy(network);
		return String.format("%s-%s", filterTitle(proxy.getTitle()), node.getName()); //$NON-NLS-1$
	}
	
	protected String getNodeId(NETWORK network, Attribute attribute) {
		NetworkProxy<NETWORK, NODE, EDGE> proxy = getNetworkProxy(network);
		return String.format("%s-%s", filterTitle(proxy.getTitle()), attribute.getName()); //$NON-NLS-1$
	}

	private String filterTitle(String title) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0 ; i < title.length(); i++) {
			char character = title.charAt(i);
			if (Character.isLetterOrDigit(character)) {
				builder.append(character);
			} else {
				builder.append("_"); //$NON-NLS-1$
			}
		}
		return builder.toString();
	}

	private void exportSynonyms(NodeProxy<NODE> proxy, Node node) {
		Collection<Gene> genes = node.getGenes();
		for (Gene gene : genes) {
			GeneNamingSource source = gene.getNamingSource();
			source.getName();
			proxy.setAttribute(source.getName(), gene.getSymbol());
		}
	}

	protected abstract NODE getNode(String id, NETWORK network);
	protected abstract NODE createNode(String id, NETWORK network);
	protected abstract NETWORK createNetwork(String title);
	protected abstract EDGE getEdge(NODE from, NODE to, String type, String label, NETWORK network);
	protected abstract EDGE getEdge(String id, NETWORK network);

	/**
	 * Creates a Cytoscape network from the interaction network given by
	 * <code>RelatedResult</code>.
	 * 
	 * @param name the name of the new <code>NetworkProxy<NETWORK, NODE, EDGE></code>
	 * @param result the results from running the GeneMANIA algorithm
	 * @param queryGenes the genes used to 
	 * @return
	 */
	@Override
	public NETWORK createNetwork(DataSet data, String name, SearchResult options, ViewStateBuilder builder, EdgeAttributeProvider attributeProvider) {
		NETWORK currentNetwork = createNetwork(name);
		NetworkProxy<NETWORK, NODE, EDGE> networkProxy = getNetworkProxy(currentNetwork);
		networkProxy.setAttribute(TYPE_ATTRIBUTE, GENEMANIA_NETWORK_TYPE);
		networkProxy.setAttribute(DATA_VERSION_ATTRIBUTE, data.getVersion().toString());
		networkProxy.setAttribute(ORGANISM_NAME_ATTRIBUTE, options.getOrganism().getName());
		networkProxy.setAttribute(NETWORKS_ATTRIBUTE, serializeNetworks(options));
		networkProxy.setAttribute(COMBINING_METHOD_ATTRIBUTE, options.getCombiningMethod().getCode());
		networkProxy.setAttribute(GENE_SEARCH_LIMIT_ATTRIBUTE, options.getGeneSearchLimit());
		networkProxy.setAttribute(ATTRIBUTE_SEARCH_LIMIT_ATTRIBUTE, options.getAttributeSearchLimit());
		networkProxy.setAttribute(ANNOTATIONS_ATTRIBUTE, serializeAnnotations(options));

		for (Group<?, ?> group : builder.getAllGroups()) {
			Group<InteractionNetworkGroup, InteractionNetwork> adapted = group.adapt(InteractionNetworkGroup.class, InteractionNetwork.class);
			if (adapted == null) {
				continue;
			}
			for (Network<InteractionNetwork> network : adapted.getNetworks()) {
				Collection<Interaction> sourceInteractions = network.getModel().getInteractions();
				if (sourceInteractions == null || sourceInteractions.size() == 0) {
					continue;
				}
				buildGraph(currentNetwork, sourceInteractions, network, attributeProvider, options, builder);
			}
		}
		
		// Add all query genes in case they don't show up in the results
		for (Gene gene : options.getQueryGenes().values()) {
			Node node = gene.getNode();
			getNode(currentNetwork, node, getSymbol(gene));
		}
		
		// Create attributes
		Map<Long, Network<Attribute>> attributesById = new HashMap<Long, Network<Attribute>>();
		for (Group<?, ?> group : builder.getAllGroups()) {
			Group<AttributeGroup, Attribute> adapted = group.adapt(AttributeGroup.class, Attribute.class);
			if (adapted == null) {
				continue;
			}
			for (Network<Attribute> network : adapted.getNetworks()) {
				attributesById.put(network.getModel().getId(), network);
			}
		}
		
		Map<Attribute, Double> weights = options.getAttributeWeights();
		for (Entry<Long, Collection<Attribute>> entry : options.getAttributesByNodeId().entrySet()) {
			Gene gene = options.getGene(entry.getKey());
			NODE to = getNode(currentNetwork, gene.getNode(), null);
			
			for (Attribute attribute : entry.getValue()) {
				String id = getNodeId(currentNetwork, attribute);
				Network<Attribute> network = attributesById.get(attribute.getId());
				
				NODE from = getNode(id, currentNetwork);
				if (from == null) {
					from = createNode(id, currentNetwork);
					NodeProxy<NODE> nodeProxy = getNodeProxy(from, currentNetwork);
					nodeProxy.setAttribute(GENE_NAME_ATTRIBUTE, attribute.getName());
					nodeProxy.setAttribute(NODE_TYPE_ATTRIBUTE, NODE_TYPE_ATTRIBUTE_NODE);
					nodeProxy.setAttribute(SCORE_ATTRIBUTE, weights.get(attribute));
					builder.addSourceNetworkForNode(nodeProxy.getIdentifier(), network);
				}
				
				String edgeLabel = attribute.getName();
				EDGE edge = getEdge(from, to, EDGE_TYPE_INTERACTION, edgeLabel, currentNetwork);
				EdgeProxy<EDGE, NODE> edgeProxy = getEdgeProxy(edge, currentNetwork);
				
				AttributeGroup group = options.getAttributeGroup(attribute.getId());
				edgeProxy.setAttribute(NETWORK_GROUP_NAME_ATTRIBUTE, group.getName());
				edgeProxy.setAttribute(ATTRIBUTE_NAME_ATTRIBUTE, edgeLabel);
				edgeProxy.setAttribute(HIGHLIGHT_ATTRIBUTE, 1);
				
				String edgeId = edgeProxy.getIdentifier();
				builder.addEdge(builder.getGroup(network), edgeId);
				builder.addSourceNetworkForEdge(edgeId, network);
			}
		}

		decorateNodes(currentNetwork, options);
		return currentNetwork;
	}
	
	private String serializeAnnotations(SearchResult options) {
		StringWriter writer = new StringWriter();
		
		JsonFactory jsonFactory = new MappingJsonFactory();
		try {
			JsonGenerator generator = jsonFactory.createJsonGenerator(writer);
			
			generator.writeStartArray();
			List<AnnotationEntry> enrichmentSummary = options.getEnrichmentSummary();
			for (AnnotationEntry entry : enrichmentSummary) {
				generator.writeStartObject();
				generator.writeFieldName("name"); //$NON-NLS-1$
				generator.writeString(entry.getName());
				generator.writeFieldName("description"); //$NON-NLS-1$
				generator.writeString(entry.getDescription());
				generator.writeFieldName("qValue"); //$NON-NLS-1$
				generator.writeNumber(entry.getQValue());
				generator.writeFieldName("sample"); //$NON-NLS-1$
				generator.writeNumber(entry.getSampleOccurrences());
				generator.writeFieldName("total"); //$NON-NLS-1$
				generator.writeNumber(entry.getTotalOccurrences());
				generator.writeEndObject();
			}
			generator.writeEndArray();
			generator.close();
		} catch (IOException e) {
			LogUtils.log(getClass(), e);
			return ""; //$NON-NLS-1$
		}
		return writer.toString();
	}

	private String serializeNetworks(SearchResult options) {
		JsonFactory factory = new MappingJsonFactory();
		StringWriter writer = new StringWriter();
		
		try {
			JsonGenerator generator = factory.createJsonGenerator(writer);
			generator.writeStartArray();
			Map<InteractionNetwork, Double> networkWeights = options.getNetworkWeights();
			for (Entry<InteractionNetwork, Double> entry : networkWeights.entrySet()) {
				generator.writeStartObject();
				InteractionNetwork network = entry.getKey();
				generator.writeFieldName("group"); //$NON-NLS-1$
				generator.writeString(options.getInteractionNetworkGroup(network.getId()).getName());
				generator.writeFieldName("name"); //$NON-NLS-1$
				generator.writeString(network.getName());
				generator.writeFieldName("weight"); //$NON-NLS-1$
				generator.writeNumber(entry.getValue());
				generator.writeEndObject();
			}
			generator.writeEndArray();
			generator.close();
		} catch (IOException e) {
			LogUtils.log(getClass(), e);
			return ""; //$NON-NLS-1$
		}
		return writer.toString();
	}

	@SuppressWarnings("unchecked")
	private void buildGraph(NETWORK currentNetwork, Collection<Interaction> interactions, Network<InteractionNetwork> network, EdgeAttributeProvider attributeProvider, SearchResult options, ViewStateBuilder builder) {
		Map<Long, Gene> queryGenes = options.getQueryGenes();
		InteractionNetwork model = network.getModel();
		
		for (Interaction interaction : interactions) {
			Node fromNode = interaction.getFromNode();
			NODE from = getNode(currentNetwork, fromNode, getSymbol(queryGenes.get(fromNode.getId())));
			
			Node toNode = interaction.getToNode();
			NODE to = getNode(currentNetwork, toNode, getSymbol(queryGenes.get(toNode.getId())));
			
			String edgeLabel = attributeProvider.getEdgeLabel(model);
			EDGE edge = getEdge(from, to, EDGE_TYPE_INTERACTION, edgeLabel, currentNetwork);
			EdgeProxy<EDGE, NODE> edgeProxy = getEdgeProxy(edge, currentNetwork);
			
			String edgeId = edgeProxy.getIdentifier();
			Double rawWeight = (double) interaction.getWeight();

			builder.addSourceNetworkForEdge(edgeId, network);
			Double weight = rawWeight * network.getWeight();
			
			List<String> networkNames = edgeProxy.getAttribute(NETWORK_NAMES_ATTRIBUTE, List.class);
			if (networkNames == null) {
				networkNames = new ArrayList<String>();
			}
			networkNames.add(network.getName());
			edgeProxy.setAttribute(NETWORK_NAMES_ATTRIBUTE, networkNames);
			
			List<Double> edgeWeights = edgeProxy.getAttribute(RAW_WEIGHTS_ATTRIBUTE, List.class);
			if (edgeWeights == null) {
				edgeWeights = new ArrayList<Double>();
			}
			edgeWeights.add((double) interaction.getWeight());
			edgeProxy.setAttribute(RAW_WEIGHTS_ATTRIBUTE, edgeWeights);

			Double oldWeight = edgeProxy.getAttribute(MAX_WEIGHT_ATTRIBUTE, Double.class);
			if (oldWeight == null || oldWeight < weight) {
				edgeProxy.setAttribute(MAX_WEIGHT_ATTRIBUTE, weight);
			}
			
			edgeProxy.setAttribute(HIGHLIGHT_ATTRIBUTE, 1);
			for (Entry<String, Object> entry : attributeProvider.getAttributes(model).entrySet()) {
				edgeProxy.setAttribute(entry.getKey(), entry.getValue());
			}
		}
	}
	
	/**
	 * Returns the symbol for the given <code>Gene</code> or <code>null</code>
	 * if the <code>Gene</code> is <code>null</code>.
	 * 
	 * @param gene
	 * @return
	 */
	private String getSymbol(Gene gene) {
		if (gene == null) {
			return null;
		}
		return gene.getSymbol();
	}

	/**
	 * Decorates the nodes in the active NETWORK with the results of
	 * the GeneMANIA algorithm.  For example, scores are assigned to the
	 * nodes.
	 * @param currentNetwork 
	 *   
	 * @param options
	 * @param queryGenes
	 */
	private void decorateNodes(NETWORK currentNetwork, SearchResult options) {
		// Assign scores.
		Map<Long, Gene> queryGenes = options.getQueryGenes();
		Map<Gene, Double> scores = options.getScores();
		
		for (Entry<Gene, Double> entry : scores.entrySet()) {
			double score = entry.getValue();
			Node node = entry.getKey().getNode();
			
			NODE cyNode = getNode(currentNetwork, node, getSymbol(queryGenes.get(node)));
			NodeProxy<NODE> nodeProxy = getNodeProxy(cyNode, currentNetwork);
	
			nodeProxy.setAttribute(LOG_SCORE_ATTRIBUTE, Math.log(score));
			nodeProxy.setAttribute(SCORE_ATTRIBUTE, score);
			String type;
			if (queryGenes.containsKey(node.getId())) {
				type = NODE_TYPE_QUERY;
			} else {
				type = NODE_TYPE_RESULT;
			}
			
			Collection<AnnotationEntry> nodeAnnotations = options.getAnnotations(node.getId());
			if (nodeAnnotations != null) {
				List<String> annotationIds = new ArrayList<String>();
				List<String> annotationNames = new ArrayList<String>();
				for (AnnotationEntry annotation : nodeAnnotations) {
					annotationIds.add(annotation.getName());
					annotationNames.add(annotation.getDescription());
				}
				nodeProxy.setAttribute(ANNOTATION_ID_ATTRIBUTE, annotationIds);
				nodeProxy.setAttribute(ANNOTATION_NAME_ATTRIBUTE, annotationNames);
			}
			
			nodeProxy.setAttribute(NODE_TYPE_ATTRIBUTE, type);
		}
	}

	@Override
	public void setHighlighted(ViewState options, NETWORK cyNetwork, boolean visible) {
		NetworkProxy<NETWORK, NODE, EDGE> networkProxy = getNetworkProxy(cyNetwork);
		for (EDGE edge : networkProxy.getEdges()) {
			EdgeProxy<EDGE, NODE> edgeProxy = getEdgeProxy(edge, cyNetwork);
			String groupName = (String) edgeProxy.getAttribute(NETWORK_GROUP_NAME_ATTRIBUTE, String.class);
			if (groupName == null) {
				continue;
			}
			Group<?, ?> group = options.getGroup(groupName);
			if (group == null) {
				continue;
			}
			Integer value = options.isEnabled(group) || visible ? 1 : 0;
			edgeProxy.setAttribute(HIGHLIGHT_ATTRIBUTE, value);
		}
		
		updateVisualStyles(cyNetwork);
		repaint();
	}
	
	protected String getVisualStyleName(NETWORK network) {
		NetworkProxy<NETWORK, NODE, EDGE> proxy = getNetworkProxy(network);
		return proxy.getTitle().replace(".", ""); //$NON-NLS-1$ //$NON-NLS-2$;
	}
	
	@Override
	public void setHighlight(ViewState config, Group<?, ?> source, NETWORK network, boolean selected) {
		Set<String> edgeIds = config.getEdgeIds(source);
		if (edgeIds == null) {
			return;
		}
		
		config.setEnabled(source, selected);
		if (selected) {
			for (String edgeId : edgeIds) {
				EDGE edge = getEdge(edgeId, network);
				EdgeProxy<EDGE, NODE> edgeProxy = getEdgeProxy(edge, network);
				edgeProxy.setAttribute(HIGHLIGHT_ATTRIBUTE, 1);
			}
		} else {
			for (String edgeId : edgeIds) {
				EDGE edge = getEdge(edgeId, network);
				EdgeProxy<EDGE, NODE> edgeProxy = getEdgeProxy(edge, network);
				edgeProxy.setAttribute(HIGHLIGHT_ATTRIBUTE, 0);
			}
		}
		
		updateVisualStyles(network);
		repaint();
	}
}
