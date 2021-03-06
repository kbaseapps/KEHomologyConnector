package keconnectorgenomehomology;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import kbasegenomes.Feature;
import kbasegenomes.Genome;
import kbaserelationengine.ConnectWSFeatures2RefOrthologsParams;
import kbaserelationengine.FeatureSequence;
import kbaserelationengine.FeatureTerms;
import kbaserelationengine.GetFeatureSequencesParams;
import kbaserelationengine.GetFeatureTermsParams;
import kbaserelationengine.GraphUpdateStat;
import kbaserelationengine.KBaseRelationEngineServiceClient;
import kbaserelationengine.StoreRichWSGenomeParams;
import kbaserelationengine.StoreWSGenomeParams;
import kbaserelationengine.WSFeature;
import keconnectorgenomehomology.util.BlastStarter;
import us.kbase.auth.AuthToken;
import us.kbase.common.service.JsonClientException;
import us.kbase.common.service.UObject;
import us.kbase.common.service.UnauthorizedException;
import us.kbase.common.utils.FastaReader;
import workspace.GetObjects2Params;
import workspace.GetObjects2Results;
import workspace.ObjectSpecification;
import workspace.WorkspaceClient;

public class KEConnectorGenomeHomologyServerImpl {

    private URL wsUrl = null;
    private URL srvWizUrl = null;
	
	private File tmpDir = null;
	private File blastBinDir = new File("bin");
//	private File blastBinDir = new File("/kb/deps/ncbi-blast-2.2.29+/bin/");
	
	private static final String MAX_EVALUE = "" + 1.0e-5;
		
	private final String[] BASE_ORTHOLOG_GUIDS = new String[]{"KBHgp746131","KBHgp779288"};
	
	private final static boolean USE_WS_ADMIN = true;
		
    public KEConnectorGenomeHomologyServerImpl(Map<String, String> config) throws MalformedURLException {
    	tmpDir = new File(config.get("scratch"));
        wsUrl = new URL(config.get("workspace-url"));
        srvWizUrl = new URL(config.get("srv-wiz-url"));
	}	
	    
	public KEAppOutput run(RunParams params, AuthToken token) throws Exception {	
		KEAppOutput appOutput = new KEAppOutput()
				.withMessage("")
				.withNewReLinks(0L)
				.withNewReNodes(0L)
				.withPropertiesSet(0L)
				.withUpdatedReNodes(0L);
		long timeStart = System.currentTimeMillis();
		
		System.out.print("Loading genome...");
		Genome wsGenome = getWSGenome(params.getObjRef(), token, USE_WS_ADMIN);
		Map<String,String> wsProteome = toProteome(wsGenome);
//		Map<String,String> wsProteome = loadTestFasta(params.getGenomeWsRef());
		System.out.println("Done! " + wsProteome.size());
				
		System.out.print("Loading base orthologs...");
		Map<String,String> baseOrtologs = getBaseOrtohlogs(token);
		System.out.println("Done  " + baseOrtologs.size());				
		
		System.out.print("Doing blastp...");
		ClosestGenomeFinder taxFinder = new ClosestGenomeFinder();
		BlastStarter.run(tmpDir, wsProteome, baseOrtologs, blastBinDir, MAX_EVALUE, taxFinder);
		System.out.println("Done");		

		if( taxFinder.isBestTaxonFound() ){
			String taxGuid = taxFinder.getBestTaxGuid();
			System.out.println("taxGuid = " + taxGuid);
			
			System.out.print("Loading ref proteome...");		
			Map<String,String> refProteome = getRefProteome(taxGuid, token);
			System.out.println("Done  " + refProteome.size());				
			
			System.out.print("Doing blastp...");		
			BBHsFinder bbhFinder = new BBHsFinder();
			BlastStarter.run(tmpDir, wsProteome, refProteome, blastBinDir, MAX_EVALUE, bbhFinder);
			List<Hit> bbhs = bbhFinder.getBBHs();
			System.out.println("Done");		
			
			// Storing results in RE
			System.out.println("Storing node and relations");
			storeRichWSGenome(params.getObjRef(), wsGenome, taxGuid,  bbhs,  token, appOutput);
			

			long timeRun = (System.currentTimeMillis() - timeStart)/1000;
			System.out.println( params.getObjRef()
					+ "\ttime=" + timeRun
					+ "\ttaxGuid=" + taxFinder.getBestTaxGuid() 
					+ "\tident=" + taxFinder.bestHit.ident
					+"\tfeature_count=" + wsProteome.size()
					+"\tbbhs_count=" + bbhs.size());
			
		}

		return appOutput;
	}

	private void storeRichWSGenome(String objRef,  Genome wsGenome, String refTaxonGuid, List<Hit> bbhs,
			AuthToken token, KEAppOutput appOutput) throws IOException, JsonClientException {
		
		KBaseRelationEngineServiceClient reClient = reClient(token);	
		
		List<FeatureTerms> featureTerms = reClient.getFeatureTerms(
				new GetFeatureTermsParams()
				.withTaxonGuid(refTaxonGuid)
				.withTermSpace("biological_process")
				);
		Hashtable<String,String> fguid2terms = new Hashtable<String,String>();
		for(FeatureTerms ft: featureTerms){
			if(ft.getTermGuids() != null && ft.getTermGuids().size() > 0){
				fguid2terms.put(ft.getFeatureGuid(), ft.getTermGuids().get(0));
			}
		}		
		
		Map<String,String> ws2refFeatureGuids = new Hashtable<String,String>();
		for(Hit hit: bbhs){
			ws2refFeatureGuids.put(wsFeatureId2GUID(objRef, hit.qname), hit.tname );
		}
		
		List<WSFeature> wsFeatures = new ArrayList<WSFeature>();
	    for (Feature ft : wsGenome.getFeatures()) {
	        String fguid = wsFeatureId2GUID(objRef, ft.getId()); 
	        String name = ft.getId();
	        List<String> aliases = new ArrayList<String>();
	        if(ft.getAliases() != null)
	        {
	        	aliases.addAll(ft.getAliases() );
	        }
	        String fFunction = ft.getFunction();
	        String refFeatureGuid = ws2refFeatureGuids.get(fguid); 
	        String termGuid = refFeatureGuid != null ? fguid2terms.get(refFeatureGuid) : null;
	        wsFeatures.add(
	        		new WSFeature()
	        		.withGuid(fguid)
	        		.withName(name)
	        		.withFunction(fFunction)
	        		.withAliases(aliases)
	        		.withRefTermGuid(termGuid));
	    }					
		
		GraphUpdateStat res = reClient.storeRichWSGenome(new StoreRichWSGenomeParams()
				.withGenomeRef(objRef)
				.withFeatures(wsFeatures)
				.withWs2refFeatureGuids(ws2refFeatureGuids)
				);
		updateAppOutput(appOutput, res); 
		
	}

	private void storeBBHs(String objRef, List<Hit> bbhs, AuthToken token, KEAppOutput appOutput) throws IOException, JsonClientException {
		KBaseRelationEngineServiceClient reClient = reClient(token);	
		
		Map<String,String> ws2refFeatureGuids = new Hashtable<String,String>();
		for(Hit hit: bbhs){
			ws2refFeatureGuids.put(wsFeatureId2GUID(objRef, hit.qname), hit.tname );
		}
		
		GraphUpdateStat res = reClient.connectWSFeatures2RefOrthologs(new ConnectWSFeatures2RefOrthologsParams()
				.withWs2refFeatureGuids(ws2refFeatureGuids));
		updateAppOutput(appOutput, res); 
	}

	private static String wsFeatureId2GUID(String objRef, String featureId){
		return "ws:" + objRef + ":feature/"  + featureId;
	}
	
	private void storeWSGenome(String objRef, Genome wsGenome, AuthToken token, KEAppOutput appOutput) throws IOException, JsonClientException {
		KBaseRelationEngineServiceClient reClient = reClient(token);		
		StoreWSGenomeParams params = new StoreWSGenomeParams();
		params.withGenomeRef(objRef);
		List<String> featureGuids = new ArrayList<String>();
	    for (Feature ft : wsGenome.getFeatures()) {
	        String fguid = wsFeatureId2GUID(objRef, ft.getId()); 
	        featureGuids.add(fguid);
	    }					
		params.withFeatureGuids(featureGuids);
		GraphUpdateStat res = reClient.storeWSGenome(params);
		updateAppOutput(appOutput, res); 
	}

	private void updateAppOutput(KEAppOutput appOutput, GraphUpdateStat res) {
		appOutput
			.withNewReLinks(appOutput.getNewReLinks() + res.getRelationshipsCreated())
			.withNewReNodes(appOutput.getNewReNodes() + res.getNodesCreated())
			.withPropertiesSet(appOutput.getPropertiesSet() + res.getPropertiesSet())
			.withUpdatedReNodes(appOutput.getUpdatedReNodes() + 0);
	}

	private Map<String, String> loadTestFasta(String genomeWsRef) {
		Map<String,String> proteome = new Hashtable<String,String>();
		Map<String,String> id2seq = FastaReader.readFromFile(new File(genomeWsRef));
		
		for(Entry<String, String> entry: id2seq.entrySet()){
			String id = entry.getKey();
			String seq = entry.getValue();
			
			String locusId = id.split(" ")[0].split(":")[1]; 
			proteome.put(locusId, seq);
		}		
		return proteome;
	}

	private void storeBBHs(List<Hit> bbhs, RunParams params) throws IOException {
		BufferedWriter bw = new BufferedWriter(new FileWriter(params.getObjRef() + ".bbhs"));
		try{
			for(Hit bbh: bbhs){
				bw.write(bbh.qname 
						+ "\t" + bbh.tname 
						+ "\t" + bbh.ident
						+ "\t" + bbh.qstart
						+ "\t" + bbh.qend
						+ "\t" + bbh.bitScore + "\n");
			}			
		} finally{
			bw.close();
		}
	}

	private Genome getWSGenome(String genomeWsRef, AuthToken token, boolean asAdmin) throws IOException, JsonClientException{
	    WorkspaceClient wsCl = new WorkspaceClient(wsUrl, token);
	    wsCl.setAllSSLCertificatesTrusted(true);
	    wsCl.setIsInsecureHttpConnectionAllowed(true);
	    
	    if (asAdmin) {
	        final Map<String, Object> command = new HashMap<>();
	        command.put("command", "getObjects");
	        command.put("params", new GetObjects2Params().withObjects(
	                Arrays.asList(new ObjectSpecification().withRef(genomeWsRef))));
	        return wsCl.administer(new UObject(command))
	                .asClassInstance(GetObjects2Results.class)
	                .getData().get(0).getData().asClassInstance(Genome.class);
	    } else {
    	    return wsCl.getObjects2(new GetObjects2Params().withObjects(
    	            Arrays.asList(new ObjectSpecification().withRef(genomeWsRef))))
    	            .getData().get(0).getData().asClassInstance(Genome.class);
	    }
	}
	
	private Map<String, String> toProteome(Genome wsGenome) {
		Map<String, String> id2seq = new Hashtable<String,String>();
	    for (Feature ft : wsGenome.getFeatures()) {
	        String id = ft.getId();
	        String protSeq = ft.getProteinTranslation();
	        if (protSeq == null) {
	            continue;
	        }
	        id2seq.put(id, protSeq);
	    }		
		return id2seq;
	}	
	
	private Map<String, String> getBaseOrtohlogs(AuthToken token) throws IOException, JsonClientException {
		Map<String, String> id2seq = new Hashtable<String,String>();
		KBaseRelationEngineServiceClient reClient = reClient(token);

		for(String ortGuid: BASE_ORTHOLOG_GUIDS){
			List<FeatureSequence> sequences = reClient.getFeatureSequences(
					new GetFeatureSequencesParams().withOrthologGuid(ortGuid));
			
			for(FeatureSequence seq: sequences){
				String id = seq.getFeatureGuid() 
						+ "|" + ortGuid 
						+ "|" + seq.getTaxonomyGuid();
				String sequence = seq.getProteinSequence(); 
				id2seq.put(id, sequence);
			}					
		}		
		return id2seq;
	}
	
	class ClosestGenomeFinder implements BlastStarter.ResultCallback{
		Hit bestHit = new Hit();
		
		@Override
		public void proteinPair(String qname, String tname, double ident, int alnLen, int mismatch, int gapopens,
				int qstart, int qend, int tstart, int tend, String eval, double bitScore) {

			if(bitScore > bestHit.bitScore){
				bestHit.setHit(qname, tname, bitScore, ident, qstart, qend);
			}
		}
		
		public boolean isBestTaxonFound(){
			return bestHit.tname != null;
		}
		
		public String getBestTaxGuid(){
			return bestHit.tname.split("\\|")[2].trim();
		}
	}	
	
	
	private Map<String, String> getRefProteome(String taxGuid,AuthToken token) throws IOException, JsonClientException {
		Map<String, String> id2seq = new Hashtable<String,String>();
		
		KBaseRelationEngineServiceClient reClient = reClient(token);
		List<FeatureSequence> sequences = reClient.getFeatureSequences(
				new GetFeatureSequencesParams().withTaxonomyGuid(taxGuid));
		
		for(FeatureSequence seq: sequences){
			id2seq.put(seq.getFeatureGuid(), seq.getProteinSequence());
		}
		return id2seq;		
	}	
	
	class Hit{
		
		String qname;
		String tname;
		double bitScore;		
		double ident;
		int qstart;
		int qend;
		
		public Hit(){};
		public Hit(String qname, String tname, double bitScore, double ident, int qstart, int qend) {
			super();
			setHit(qname, tname, bitScore, ident, qstart, qend);
		}

		public void setHit(String qname, String tname, double bitScore, double ident, int qstart, int qend) {
			this.qname = qname;
			this.tname = tname;
			this.bitScore = bitScore;
			this.ident = ident;
			this.qstart = qstart;
			this.qend = qend;
		}						
	}	
	
	class PartnerScore{
		String name;
		double bitScore;
		public PartnerScore(String name, double bitScore) {
			set(name, bitScore);
		}
		public void set(String name, double bitScore) {
			this.name = name;
			this.bitScore = bitScore;
		}		
	}
	
	class BBHsFinder implements BlastStarter.ResultCallback{
		Hashtable<String, Hit> q2t = new Hashtable<String, Hit>();
		Hashtable<String, PartnerScore> t2q = new Hashtable<String, PartnerScore>();
				
		@Override
		public void proteinPair(String qname, String tname, double ident, int alnLen, int mismatch, int gapopens,
				int qstart, int qend, int tstart, int tend, String eval, double bitScore) {
			
			// Do q2t
			Hit hit = q2t.get(qname);
			if(hit == null){
				hit = new Hit(qname, tname, bitScore, ident, qstart, qend);
				q2t.put(qname, hit);
			}
			if(bitScore > hit.bitScore){
				hit.setHit(qname, tname, bitScore, ident, qstart, qend);
			}
			
			// Do t2q
			PartnerScore ps = t2q.get(tname);
			if(ps == null){
				ps = new PartnerScore(qname, bitScore);
				t2q.put(tname, ps);
			}
			if(bitScore > ps.bitScore){
				ps.set(qname, bitScore);
			}			
		}	
		
		public List<Hit> getBBHs(){
			List<Hit> bbhs = new ArrayList<Hit>();
			
			for(Entry<String, Hit> entry :q2t.entrySet()){
				String qname = entry.getKey();
				Hit hit = entry.getValue();
				PartnerScore ps = t2q.get(hit.tname);
				if(ps != null && ps.name.equals(qname)){
					bbhs.add(hit);
				}
			}
			return bbhs;
		}
	}	
	
	private KBaseRelationEngineServiceClient reClient(AuthToken token) throws UnauthorizedException, IOException{
        KBaseRelationEngineServiceClient client = new KBaseRelationEngineServiceClient(srvWizUrl, token);
        client.setIsInsecureHttpConnectionAllowed(true);
        client.setServiceVersion("dev");
        return client;    	
	}
	
	public static void main(String[] args) throws Exception {
		KEConnectorGenomeHomologyServerImpl	impl = new KEConnectorGenomeHomologyServerImpl(System.getenv());
		String token = System.getenv("token");
		String user = System.getenv("user");
		
		impl.run(new RunParams().withObjRef("25582/28/1"), new AuthToken(token, user));
//		File rootDir = new File("/kb/module/work/tmp/");
//		for(File file: rootDir.listFiles()){
//			if(file.getName().endsWith("faa")){
//				impl.run(new RunParams().withGenomeWsRef(file.getAbsolutePath()), 
//						new AuthToken(token, user));						
//			}
//		}
		
	}
	
}
