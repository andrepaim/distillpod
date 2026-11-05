#!/usr/bin/env python3
"""Score complete human assessments of blind distill pairs; never substitute model self-grades."""
import argparse
import json
import statistics


def summarize(pairs, key, reviews):
    ids = [row['id'] for row in pairs]
    if len(ids) != len(set(ids)) or set(key) != set(ids):
        raise ValueError('Pair IDs and answer key must match exactly')
    by_id = {row['id']: row for row in reviews}
    if len(by_id) != len(reviews) or set(by_id) != set(ids):
        raise ValueError('Every pair needs exactly one human assessment')
    critical_failures = []
    wins = held_wins = held_count = 0
    scores = []
    for pair in pairs:
        case_id = pair['id']
        proposed = key[case_id]
        if proposed not in ('A', 'B'):
            raise ValueError('Invalid answer key')
        review = by_id[case_id]
        if review['preferred'] not in ('A', 'B', 'tie'):
            raise ValueError('Invalid preference')
        for side in ('A', 'B'):
            assessment = review[side]
            if type(assessment['criticalFailure']) is not bool:
                raise ValueError('criticalFailure must be a human boolean judgment')
            for metric in ('complete', 'faithful', 'standalone', 'relevant'):
                if type(assessment[metric]) is not int or assessment[metric] not in (0, 1, 2):
                    raise ValueError('Scores must be 0 (fails), 1 (partial), or 2 (passes)')
        selected = review[proposed]
        if selected['criticalFailure']:
            critical_failures.append(case_id)
        win = review['preferred'] == proposed
        wins += win
        if pair['heldOut']:
            held_count += 1
            held_wins += win
        scores.append(selected)
    timings = [row.get('generationMs', 0) + row.get('reviewMs', 0) for row in pairs]
    rate = held_wins / held_count if held_count else None
    return dict(
        cases=len(pairs), proposedWins=wins, criticalFailures=critical_failures,
        heldOutCount=held_count, heldOutPreferenceRate=rate,
        meanScores={metric: statistics.mean(s[metric] for s in scores) if scores else None
                    for metric in ('complete', 'faithful', 'standalone', 'relevant')},
        medianAiMs=statistics.median(timings) if timings else None,
        generationCalls=sum(row.get('generationCalls', 0) for row in pairs),
        reviewCalls=sum(row.get('reviewCalls', 0) for row in pairs),
        qualityGatePassed=bool(pairs) and not critical_failures and held_count >= 20 and rate >= .8,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pairs')
    parser.add_argument('key')
    parser.add_argument('reviews')
    args = parser.parse_args()
    def read(path):
        with open(path, encoding='utf-8') as file:
            return json.load(file)
    print(json.dumps(summarize(read(args.pairs), read(args.key), read(args.reviews)), indent=2))


if __name__ == '__main__':
    main()
