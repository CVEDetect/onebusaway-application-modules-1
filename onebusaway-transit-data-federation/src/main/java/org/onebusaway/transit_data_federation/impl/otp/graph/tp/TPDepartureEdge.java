package org.onebusaway.transit_data_federation.impl.otp.graph.tp;

import java.util.List;

import org.onebusaway.collections.tuple.Pair;
import org.onebusaway.transit_data_federation.impl.otp.GraphContext;
import org.onebusaway.transit_data_federation.impl.otp.ItineraryWeightingLibrary;
import org.onebusaway.transit_data_federation.impl.otp.OBAStateData.OBAEditor;
import org.onebusaway.transit_data_federation.impl.otp.OBATraverseOptions;
import org.onebusaway.transit_data_federation.impl.otp.graph.AbstractEdge;
import org.onebusaway.transit_data_federation.impl.otp.graph.EdgeNarrativeImpl;
import org.onebusaway.transit_data_federation.model.TargetTime;
import org.onebusaway.transit_data_federation.services.ArrivalAndDepartureService;
import org.onebusaway.transit_data_federation.services.realtime.ArrivalAndDepartureInstance;
import org.onebusaway.transit_data_federation.services.transit_graph.StopEntry;
import org.opentripplanner.routing.algorithm.NegativeWeightException;
import org.opentripplanner.routing.core.EdgeNarrative;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.TraverseOptions;
import org.opentripplanner.routing.core.TraverseResult;
import org.opentripplanner.routing.core.Vertex;

public class TPDepartureEdge extends AbstractEdge {

  private TPState _pathState;

  public TPDepartureEdge(GraphContext context, TPState pathState) {
    super(context);
    _pathState = pathState;
  }

  @Override
  public TraverseResult traverse(State s0, TraverseOptions options)
      throws NegativeWeightException {

    OBATraverseOptions obaOpts = (OBATraverseOptions) options;

    ArrivalAndDepartureService adService = _context.getArrivalAndDepartureService();

    Vertex fromV = new TPDepartureVertex(_context, _pathState);

    Pair<StopEntry> stopPair = _pathState.getStops();

    TargetTime targetTime = new TargetTime(s0.getTime(), obaOpts.currentTime);
    int lookaheadTime = 0;
    if (s0.getData().getNumBoardings() == 0)
      lookaheadTime = obaOpts.lookaheadTime;

    List<Pair<ArrivalAndDepartureInstance>> instances = adService.getNextDeparturesForStopPair(
        stopPair.getFirst(), stopPair.getSecond(), targetTime,
        obaOpts.numItineraries, obaOpts.useRealtime, lookaheadTime);

    TraverseResult results = null;

    for (Pair<ArrivalAndDepartureInstance> pair : instances) {

      ArrivalAndDepartureInstance departure = pair.getFirst();
      if (departure.getBestDepartureTime() < s0.getTime() - lookaheadTime
          * 1000)
        continue;

      Vertex toV = new TPBlockDepartureVertex(_context, _pathState, departure,
          pair.getSecond());

      int dwellTime = computeWaitTime(s0, pair);

      double w = ItineraryWeightingLibrary.computeWeightForWait(options,
          dwellTime, s0);

      OBAEditor s1 = (OBAEditor) s0.edit();
      s1.setTime(departure.getBestDepartureTime());

      /**
       * If the departure time is less than the starting state time, it must
       * mean the departure was included as determined by the lookahead
       * parameter. Thus, we indicate that we have a lookahead itinerary.
       */
      if (departure.getBestDepartureTime() < s0.getTime())
        s1.setLookaheadItinerary();

      if (departure.getBlockSequence() != null)
        s1.appendTripSequence(departure.getBlockSequence());
      else
        s1.appendTripSequence(departure.getBlockTrip());

      if (w < 0)
        System.out.println("here");

      EdgeNarrative narrative = new EdgeNarrativeImpl(fromV, toV);
      TraverseResult r = new TraverseResult(w, s1.createState(), narrative);
      results = r.addToExistingResultChain(results);
    }

    return results;
  }

  @Override
  public TraverseResult traverseBack(State s0, TraverseOptions options)
      throws NegativeWeightException {

    TPDepartureVertex fromVertex = new TPDepartureVertex(_context, _pathState);
    Vertex toVertex = null;
    EdgeNarrativeImpl narrative = new EdgeNarrativeImpl(fromVertex, toVertex);

    return new TraverseResult(0, s0, narrative);
  }

  /****
   * 
   ****/

  private int computeWaitTime(State s0, Pair<ArrivalAndDepartureInstance> pair) {
    int waitTime = (int) ((pair.getFirst().getBestDepartureTime() - s0.getTime()) / 1000);
    // If we have a lookahead departure, the wait time will actually be zero
    return Math.max(waitTime, 0);
  }
}
