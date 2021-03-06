package ru.fedor.conway.life.stream.client.reactor.flow;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;
import reactor.util.function.Tuple4;
import ru.fedor.conway.life.stream.client.reactor.flow.book.AbstractBookReader;
import ru.fedor.conway.life.stream.client.reactor.flow.book.BookReaderEng;
import ru.fedor.conway.life.stream.client.reactor.flow.book.BookReaderFlux;
import ru.fedor.conway.life.stream.client.reactor.flow.book.BookReaderRus;
import ru.fedor.conway.life.stream.client.reactor.flow.life.ConwayServerJsonProcessor;
import ru.fedor.conway.life.stream.client.reactor.flow.life.ConwayServerWebSocketClient;
import ru.fedor.conway.life.stream.client.reactor.flow.stats.*;

import java.time.Duration;

@Getter
@Slf4j
public class Pipeline {

	private final ConwayServerWebSocketClient serverWebSocketClient;
	private final int conwayServerStatsFlushDelayMs;
	private final int wordDelayMs;

	private final BookReaderRus bookReaderRus = BookReaderRus.newInstance();
	private final BookReaderEng bookReaderEng = BookReaderEng.newInstance();
	private final WordStatCollectorEng wordStatCollectorEng = new WordStatCollectorEng();
	private final WordStatCollectorRus wordStatCollectorRus = new WordStatCollectorRus();
	private Flux<AggregatedStats> flux;
	private final ConwayServerStatsCollector conwayServerStatsCollector = new ConwayServerStatsCollector();
	private ConnectableFlux<AggregatedStats> hotFlux;


	public Pipeline(ConwayServerWebSocketClient serverWebSocketClient, int conwayServerStatsFlushDelayMs, int wordDelayMs) {
		this.serverWebSocketClient = serverWebSocketClient;
		this.conwayServerStatsFlushDelayMs = conwayServerStatsFlushDelayMs;
		this.wordDelayMs = wordDelayMs;
	}

	public void buildAndStartPipeline() {
		var engFlux = createWordStatFlux(bookReaderEng, wordStatCollectorEng);
		var rusFlux = createWordStatFlux(bookReaderRus, wordStatCollectorRus);

		serverWebSocketClient.openStream();
		hotFlux = serverWebSocketClient.getFlux()
				.window(Duration.ofMillis(conwayServerStatsFlushDelayMs))
				.flatMap(windowFlux -> processBatches(windowFlux, engFlux, rusFlux))
				.map(tp -> AggregatedStats.create(tp.getT1(), tp.getT2(), tp.getT3(), tp.getT4()))
				.publish();

		flux = hotFlux.autoConnect(0);
	}

	private Mono<Tuple4<ConwayServerStats, WordStats, WordStats, Long>> processBatches(Flux<String> windowFlux, Flux<WordStats> engFlux, Flux<WordStats> rusFlux) {
		var amount = windowFlux
				.map(ConwayServerJsonProcessor::parseEvent)
				.collectList()
				.map(conwayServerStatsCollector::calculate);
		var engStat = engFlux
				.take(1)
				.single();
		var rusStat = rusFlux
				.take(1)
				.single();
		return Mono.zip(amount, engStat, rusStat, Mono.just(System.currentTimeMillis()));
	}

	private <SC extends AbstractWordStatCollector> Flux<WordStats> createWordStatFlux(AbstractBookReader bookReader, SC wordStatCollector) {
		var collectorSync = new WordStatsCollectorSynchronizedDecorator<>(wordStatCollector);
		BookReaderFlux.createFluxReader(bookReader)
				.delayElements(Duration.ofMillis(wordDelayMs))
				.subscribe(collectorSync::analyse);

		return Flux.generate(() -> collectorSync, this::getNextWordStatsAndReturnState);
	}

	private <SC extends AbstractWordStatCollector> WordStatsCollectorSynchronizedDecorator<SC> getNextWordStatsAndReturnState(WordStatsCollectorSynchronizedDecorator<SC> collector, SynchronousSink<WordStats> sink) {
		sink.next(collector.getStatsAndReset());
		return collector;
	}

}
